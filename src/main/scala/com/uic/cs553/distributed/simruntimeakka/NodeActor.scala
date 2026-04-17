package com.uic.cs553.distributed.simruntimeakka

import akka.actor.{Actor, ActorRef, Props, Timers}
import com.uic.cs553.distributed.simcore.{MessageType, NodePdf, PdfSampler}
import org.slf4j.LoggerFactory
import scala.concurrent.duration.FiniteDuration

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

final class NodeActor(
                       id:         Int,
                       metrics:    MetricsCollector,
                       sampler:    PdfSampler,
                       algorithms: List[DistributedAlgorithm]
                     ) extends Actor with Timers:

  import NodeActor.*
  private val logger = LoggerFactory.getLogger(s"Node-$id")

  // ── Before Init: only accept Init message ─────────────────
  override def receive: Receive = uninitialized

  private def uninitialized: Receive =
    case Init(neighbors, allowedOnEdge, pdf, timerEnabled, tickEvery) =>
      val ctx = NodeContext(id, neighbors, allowedOnEdge, metrics)
      logger.info(s"Node $id initialized: ${neighbors.size} neighbors, timerEnabled=$timerEnabled")

      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEvery)

      algorithms.foreach(_.onStart(ctx))

      // Switch to initialized behavior with immutable state
      context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, workQueue = List.empty))

  // ── After Init: all state passed as parameters ─────────────
  private def initialized(
                           neighbors:     Map[Int, ActorRef],
                           allowedOnEdge: Map[Int, Set[MessageType]],
                           pdf:           NodePdf,
                           ctx:           NodeContext,
                           workQueue:     List[String]
                         ): Receive =

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

      if kind == MessageType.WORK then
        val newQueue = payload :: workQueue
        val (updatedQueue, didPropagate) = processWork(newQueue, neighbors, allowedOnEdge)
        context.become(initialized(neighbors, allowedOnEdge, pdf, ctx, updatedQueue))

    case AlgorithmMsg(from, algName, payload) =>
      algorithms
        .find(_.name == algName)
        .foreach(_.onMessage(ctx, AlgorithmMsg(from, algName, payload)))

    case Stop =>
      logger.info(s"Node $id stopping")
      timers.cancelAll()
      context.stop(self)

  // ── Pure helper — no side effects on state ─────────────────
  private def processWork(
                           queue:         List[String],
                           neighbors:     Map[Int, ActorRef],
                           allowedOnEdge: Map[Int, Set[MessageType]]
                         ): (List[String], Boolean) =
    queue match
      case head :: tail =>
        logger.debug(s"Node $id processing work: $head")
        val propagated = if math.random() < 0.5 then
          sendToEligibleNeighbor(neighbors, allowedOnEdge, MessageType.WORK, s"work-from-$id")
          true
        else false
        (tail, propagated)
      case Nil =>
        (Nil, false)

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