package com.uic.cs553.distributed.simruntimeakka

import akka.actor.{Actor, ActorRef, Props, Timers}
import com.uic.cs553.distributed.simcore.{MessageType, NodePdf, PdfSampler}
import org.slf4j.LoggerFactory
import scala.concurrent.duration.*

object NodeActor:
  def props(
             id:         Int,
             metrics:    MetricsCollector,
             sampler:    PdfSampler,
             algorithms: List[DistributedAlgorithm] = List.empty
           ): Props =
    Props(new NodeActor(id, metrics, sampler, algorithms))

  sealed trait Msg
  final case class Init(
                         neighbors:     Map[Int, ActorRef],
                         allowedOnEdge: Map[Int, Set[MessageType]],
                         pdf:           NodePdf,
                         timerEnabled:  Boolean,
                         tickEvery:     FiniteDuration
                       ) extends Msg
  final case class ExternalInput(kind: MessageType, payload: String) extends Msg
  final case class Envelope(from: Int, kind: MessageType, payload: String) extends Msg
  final case class AlgorithmMsg(from: Int, algName: String, payload: String) extends Msg
  case object Stop extends Msg
  private case object Tick extends Msg
  // ── Sent by scheduler 500ms after Init to start algorithms ────
  // Ensures all actors are fully initialized before any algorithm
  // sends CONTROL messages (wave markers, snapshot markers).
  // Without this delay, markers arrive as dead letters because
  // destination actors are still in uninitialized state.
  private case object StartAlgorithms extends Msg

final class NodeActor(
                       id:         Int,
                       metrics:    MetricsCollector,
                       sampler:    PdfSampler,
                       algorithms: List[DistributedAlgorithm]
                     ) extends Actor with Timers:

  import NodeActor.*
  private val logger = LoggerFactory.getLogger(s"Node-$id")

  override def receive: Receive = uninitialized

  // ── Before Init: only accept Init message ─────────────────────
  private def uninitialized: Receive =
    case Init(neighbors, allowedOnEdge, pdf, timerEnabled, tickEvery) =>
      val ctx = NodeContext(id, neighbors, allowedOnEdge, metrics)
      logger.info(s"Node $id initialized: ${neighbors.size} neighbors, timerEnabled=$timerEnabled")

      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEvery)

      // Delay algorithm start by 500ms — lets all actors finish Init
      // before any algorithm sends protocol messages (CONTROL type).
      // This eliminates the dead letters race condition where snapshot
      // markers arrive at actors still in uninitialized state.
      context.system.scheduler.scheduleOnce(
        500.millis, self, StartAlgorithms
      )(context.dispatcher)

      context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, workQueue = List.empty))

  // ── After Init: all state passed as immutable parameters ───────
  private def initialized(
                           neighbors:     Map[Int, ActorRef],
                           allowedOnEdge: Map[Int, Set[MessageType]],
                           pdf:           NodePdf,
                           ctx:           NodeContext,
                           workQueue:     List[String]
                         ): Receive =

    case StartAlgorithms =>
      // All actors are now initialized — safe to start algorithms.
      // Initiator node (0) will call onStart which triggers wave/snapshot.
      logger.debug(s"Node $id: starting algorithms")
      algorithms.foreach(_.onStart(ctx))

    case Tick =>
      val kind = sampler.sample(pdf)
      sendToEligibleNeighbor(neighbors, allowedOnEdge, kind, s"tick-from-$id")
      algorithms.foreach(_.onTick(ctx))

    case ExternalInput(kind, payload) =>
      logger.info(s"Node $id received external input: $kind")
      sendToEligibleNeighbor(neighbors, allowedOnEdge, kind, payload)
      metrics.recordReceived(id, kind)

    case Envelope(from, kind, payload) =>
      logger.debug(s"Node $id received $kind from $from")
      metrics.recordReceived(id, kind)
      algorithms.foreach(_.onMessage(ctx, Envelope(from, kind, payload)))

      kind match

        case MessageType.WORK =>
          // WORK cascades with 50% probability — models task delegation chains.
          // Each node that receives WORK may pass it further down the graph.
          // Uses a workQueue to process one item per receive — actor-safe.
          val newQueue = payload :: workQueue
          val (updatedQueue, _) = processWork(newQueue, neighbors, allowedOnEdge)
          context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, updatedQueue))

        case MessageType.GOSSIP =>
          // GOSSIP propagates to one eligible neighbor excluding the sender.
          // Models classic gossip protocol — spreads hop by hop respecting
          // edge label constraints. filter(_ != from) prevents bounce-back.
          val eligible = neighbors.keys
            .filter(_ != from)
            .filter(to => allowedOnEdge.getOrElse(to, Set.empty).contains(MessageType.GOSSIP))
            .toList

          eligible match
            case Nil =>
              logger.debug(s"Node $id: GOSSIP from $from — no eligible forward targets, stopping")
            case candidates =>
              val to = candidates(scala.util.Random.nextInt(candidates.size))
              neighbors(to) ! Envelope(from = id, kind = MessageType.GOSSIP,
                payload = s"gossip-fwd-$id:$payload")
              metrics.recordSent(id, to, MessageType.GOSSIP)
              logger.debug(s"Node $id: forwarded GOSSIP to Node $to")

        case MessageType.PING =>
          // PING is a health check — attempt to send ACK back to sender.
          // ACK is only possible if a reverse edge exists (from→sender)
          // AND that edge's label allows ACK. In a directed graph, the
          // forward edge (sender→us) does not guarantee a reverse edge.
          // If no reverse path exists, PING is silently terminal.
          logger.debug(s"Node $id: PING from $from — sending ACK if possible")
          val canAck = neighbors.contains(from) &&
            allowedOnEdge.getOrElse(from, Set.empty).contains(MessageType.ACK)
          if canAck then
            neighbors(from) ! Envelope(from = id, kind = MessageType.ACK,
              payload = s"ack-from-$id")
            metrics.recordSent(id, from, MessageType.ACK)
            logger.debug(s"Node $id: ACK sent to Node $from")
          else
            logger.debug(s"Node $id: no reverse edge to $from or ACK blocked — PING terminal")

        case MessageType.CONTROL =>
          // CONTROL handled entirely by algorithm modules via onMessage above.
          // NodeActor does nothing extra — Wave/LaiYang decide what to do.
          ()

        case MessageType.ACK =>
          // ACK is the reply to a PING — confirms the sender is alive.
          // Terminal at receiver — no further forwarding needed.
          logger.debug(s"Node $id: ACK from $from — node $from is alive")

    case AlgorithmMsg(from, algName, payload) =>
      algorithms
        .find(_.name == algName)
        .foreach(_.onMessage(ctx, AlgorithmMsg(from, algName, payload)))

    case Stop =>
      logger.info(s"Node $id stopping")
      timers.cancelAll()
      context.stop(self)

  // ── processWork — pure, no side effects on actor state ─────────
  // 50% chance to propagate WORK to an eligible neighbor.
  private def processWork(
                           queue:         List[String],
                           neighbors:     Map[Int, ActorRef],
                           allowedOnEdge: Map[Int, Set[MessageType]]
                         ): (List[String], Boolean) =
    queue match
      case head :: tail =>
        logger.debug(s"Node $id processing work: $head")
        val propagated = if math.random() < 0.5 then
          sendToEligibleNeighbor(neighbors, allowedOnEdge,
            MessageType.WORK, s"work-from-$id")
          true
        else false
        (tail, propagated)
      case Nil =>
        (Nil, false)

  // ── sendToEligibleNeighbor — checks edge labels before sending ──
  // Drops message and records metric if no eligible neighbor found.
  private def sendToEligibleNeighbor(
                                      neighbors:     Map[Int, ActorRef],
                                      allowedOnEdge: Map[Int, Set[MessageType]],
                                      kind:          MessageType,
                                      payload:       String
                                    ): Unit =
    val eligible = neighbors.keys.filter: to =>
      allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
    .toList

    eligible match
      case Nil =>
        logger.debug(s"Node $id: no eligible neighbor for $kind — dropping")
        metrics.recordDropped(id, -1, kind)
      case candidates =>
        val to = candidates(scala.util.Random.nextInt(candidates.size))
        neighbors(to) ! Envelope(from = id, kind = kind, payload = payload)
        metrics.recordSent(id, to, kind)
        logger.info(s"Node $id → Node $to: $kind '$payload'")