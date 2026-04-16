package com.uic.cs553.distributed.simruntimeakka

import akka.actor.{Actor, ActorRef, Props, Timers}
import com.uic.cs553.distributed.simcore.{MessageType, NodePdf, PdfSampler}
import com.uic.cs553.distributed.simruntimeakka.{MetricsCollector, NodeContext}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/**
 * Core actor representing one graph node in the simulation.
 *
 * Each node = one Actor = one "microservice instance"
 * Processes ONE message at a time (thread safe by design)
 * Communicates ONLY with direct neighbors via ActorRefs
 *
 * Responsibilities:
 * 1. Generate traffic based on NodePdf (timer-driven)
 * 2. Receive and forward messages (respecting EdgeLabels)
 * 3. Accept external injected messages (input node mode)
 * 4. Delegate to algorithm modules via NodeContext
 */
object NodeActor:
  def props(
             id:       Int,
             metrics:  MetricsCollector,
             sampler:  PdfSampler,
             algorithms: List[DistributedAlgorithm] = List.empty
           ): Props =
    Props(new NodeActor(id, metrics, sampler, algorithms))

  // ── Message protocol ────────────────────────────────────────
  sealed trait Msg

  /** Sent once at startup — configures neighbors, PDF, timer */
  final case class Init(
                         neighbors:     Map[Int, ActorRef],
                         allowedOnEdge: Map[Int, Set[MessageType]],
                         pdf:           NodePdf,
                         timerEnabled:  Boolean,
                         tickEvery:     FiniteDuration
                       ) extends Msg

  /** External message injected by CLI driver or test harness */
  final case class ExternalInput(
                                  kind:    MessageType,
                                  payload: String
                                ) extends Msg

  /** Normal message flowing between nodes */
  final case class Envelope(
                             from:    Int,
                             kind:    MessageType,
                             payload: String
                           ) extends Msg

  /** Algorithm-level control message — always allowed */
  final case class AlgorithmMsg(
                                 from:    Int,
                                 algName: String,
                                 payload: String
                               ) extends Msg

  /** Shutdown signal */
  case object Stop extends Msg

  /** Internal timer tick — triggers PDF-based message generation */
  private case object Tick extends Msg

/**
 * NodeActor implementation.
 * Extends Actor (Akka Classic) with Timers for scheduled ticks.
 */
final class NodeActor(
                       id:         Int,
                       metrics:    MetricsCollector,
                       sampler:    PdfSampler,
                       algorithms: List[DistributedAlgorithm]
                     ) extends Actor with Timers:

  import NodeActor.*
  private val logger = LoggerFactory.getLogger(s"Node-$id")

  // ── Mutable state (actor-local, no sharing, no locks needed) ─
  // justified: actor processes one message at a time
  private var neighbors:     Map[Int, ActorRef]        = Map.empty
  private var allowedOnEdge: Map[Int, Set[MessageType]] = Map.empty
  private var pdf:           NodePdf                   = NodePdf.uniform
  private var ctx:           NodeContext               = _

  // Work queue for WORK propagation model
  // justified: actor-local mutable state, single-threaded access
  private var workQueue: List[String] = List.empty

  override def receive: Receive =

    case Init(nbrs, allowed, nodePdf, timerEnabled, tickEvery) =>
      neighbors     = nbrs
      allowedOnEdge = allowed
      pdf           = nodePdf
      ctx = NodeContext(id, neighbors, allowedOnEdge, metrics)

      logger.info(s"Node $id initialized: ${neighbors.size} neighbors, timerEnabled=$timerEnabled")

      if timerEnabled then
        timers.startTimerAtFixedRate("tick", Tick, tickEvery)

      // Notify algorithms that this node has started
      algorithms.foreach(_.onStart(ctx))

    case Tick =>
      // Sample message type from PDF and send to eligible neighbor
      val kind = sampler.sample(pdf)
      sendToEligibleNeighbor(kind, payload = s"tick-from-$id")
      algorithms.foreach(_.onTick(ctx))

    case ExternalInput(kind, payload) =>
      logger.info(s"Node $id received external input: $kind")
      sendToEligibleNeighbor(kind, payload)
      metrics.recordReceived(id, kind)

    case Envelope(from, kind, payload) =>
      logger.debug(s"Node $id received $kind from $from")
      metrics.recordReceived(id, kind)

      // Work propagation model — WORK messages go into queue
      if kind == MessageType.WORK then
        workQueue = payload :: workQueue
        processWork()

      // Delegate to algorithm modules
      algorithms.foreach(_.onMessage(ctx, Envelope(from, kind, payload)))

    case AlgorithmMsg(from, algName, payload) =>
      // Algorithm control messages bypass edge label checks
      algorithms
        .find(_.name == algName)
        .foreach(_.onMessage(ctx, AlgorithmMsg(from, algName, payload)))

    case Stop =>
      logger.info(s"Node $id stopping")
      timers.cancelAll()
      context.stop(self)

  /**
   * Work propagation: process one work item, potentially
   * spawning more WORK to neighbors. Models a terminating computation.
   */
  private def processWork(): Unit =
    workQueue match
      case head :: tail =>
        workQueue = tail
        logger.debug(s"Node $id processing work: $head")
        // 50% chance to propagate work to a neighbor
        if math.random() < 0.5 then
          sendToEligibleNeighbor(MessageType.WORK, s"work-from-$id")
      case Nil => ()

  /**
   * Send a message to ONE eligible neighbor.
   * Eligible = edge exists AND message type is allowed on that edge.
   * If multiple eligible, picks one. If none, drops message.
   */
//  private def sendToEligibleNeighbor(kind: MessageType, payload: String): Unit =
//    val eligible = neighbors.keys.filter: to =>
//      allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
//    .toList
//
//    eligible match
//      case Nil =>
//        logger.debug(s"Node $id: no eligible neighbor for $kind")
//      case candidates =>
//        val to = candidates(scala.util.Random.nextInt(candidates.size))
//        neighbors(to) ! Envelope(from = id, kind = kind, payload = payload)
//        metrics.recordSent(id, to, kind)
  private def sendToEligibleNeighbor(kind: MessageType, payload: String): Unit =
    val eligible = neighbors.keys.filter: to =>
      allowedOnEdge.getOrElse(to, Set.empty).contains(kind)
    .toList

    eligible match
      case Nil =>
        logger.debug(s"Node $id: no eligible neighbor for $kind — dropping")
        metrics.recordDropped(id, -1, kind) // ← ADD THIS LINE
      case candidates =>
        val to = candidates(scala.util.Random.nextInt(candidates.size))
        neighbors(to) ! Envelope(from = id, kind = kind, payload = payload)
        metrics.recordSent(id, to, kind)
        logger.info(s"Node $id → Node $to: $kind '$payload'")