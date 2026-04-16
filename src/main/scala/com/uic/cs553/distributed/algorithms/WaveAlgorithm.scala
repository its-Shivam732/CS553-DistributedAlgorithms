package com.uic.cs553.distributed.algorithms

import com.uic.cs553.distributed.simruntimeakka.{
  DistributedAlgorithm, NodeContext
}
import WaveMessages.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Wave Algorithm Implementation.
 *
 * A wave is a distributed computation where:
 * 1. Every node participates exactly once
 * 2. There is at least one "decision" event (at the initiator)
 * 3. The wave has a defined termination
 *
 * This implements the Echo Algorithm variant of wave algorithms:
 * - Initiator sends Wave to all neighbors
 * - Internal nodes forward Wave, collect Acks from all children
 * - Leaf nodes immediately Ack
 * - Initiator decides when all Acks received
 *
 * System assumptions (required by course spec):
 * - Asynchronous message passing
 * - Reliable channels (no message loss)
 * - General graph topology (not restricted to ring or tree)
 * - No process failures
 *
 * @param isInitiator true for the node that starts the wave
 */
class WaveAlgorithm(isInitiator: Boolean = false)
  extends DistributedAlgorithm:

  override val name: String = "WaveAlgorithm"
  private val logger = LoggerFactory.getLogger(s"$name")

  // ── Algorithm state (per node) ───────────────────────────────
  // justified: single-threaded actor access, no sharing
  private var phase:       Phase        = Phase.IDLE
  private var parent:      Option[Int]  = None
  private var pendingAcks: Int          = 0
  private var waveId:      String       = ""
  private var visited:     Set[Int]     = Set.empty
  private var startTime:   Long         = 0L

  enum Phase:
    case IDLE, ACTIVE, DONE

  override def onStart(ctx: NodeContext): Unit =
    if isInitiator then
      logger.info(s"Node ${ctx.nodeId}: initiating wave")
      startWave(ctx)

  override def onMessage(ctx: NodeContext, msg: Any): Unit =
    msg match
      case Wave(id, from) =>
        handleWave(ctx, id, from)

      case WaveAck(id, from) =>
        handleAck(ctx, id, from)

      case _ => () // ignore non-wave messages

  override def onTick(ctx: NodeContext): Unit = ()

  // ── Private handlers ────────────────────────────────────────

  private def startWave(ctx: NodeContext): Unit =
    waveId    = UUID.randomUUID().toString.take(8)
    phase     = Phase.ACTIVE
    parent    = None  // initiator has no parent
    startTime = System.currentTimeMillis()
    visited   = Set(ctx.nodeId)

    val neighbors = ctx.neighbors.toList
    if neighbors.isEmpty then
      // Lone node — wave immediately complete
      logger.info(s"Node ${ctx.nodeId}: wave $waveId complete (no neighbors)")
      phase = Phase.DONE
    else
      pendingAcks = neighbors.size
      logger.info(s"Node ${ctx.nodeId}: sending wave $waveId to $neighbors")
      neighbors.foreach: to =>
        ctx.send(to, com.uic.cs553.distributed.simcore.MessageType.CONTROL,
          s"WAVE:$waveId:${ctx.nodeId}")

  private def handleWave(ctx: NodeContext, id: String, from: Int): Unit =
    phase match
      case Phase.IDLE =>
        // First time receiving wave — become active
        waveId  = id
        phase   = Phase.ACTIVE
        parent  = Some(from)
        visited = Set(ctx.nodeId)

        // Forward to all neighbors EXCEPT parent
        val forwardTo = ctx.neighbors.filterNot(_ == from).toList
        logger.info(s"Node ${ctx.nodeId}: received wave $id from $from, forwarding to $forwardTo")

        if forwardTo.isEmpty then
          // Leaf node — immediately ack parent
          logger.info(s"Node ${ctx.nodeId}: leaf node, acking parent $from")
          ctx.send(from, com.uic.cs553.distributed.simcore.MessageType.CONTROL,
            s"WAVEACK:$id:${ctx.nodeId}")
          phase = Phase.DONE
        else
          pendingAcks = forwardTo.size
          forwardTo.foreach: to =>
            ctx.send(to, com.uic.cs553.distributed.simcore.MessageType.CONTROL,
              s"WAVE:$id:${ctx.nodeId}")

      case Phase.ACTIVE | Phase.DONE =>
        // Already seen this wave — send ack immediately
        ctx.send(from, com.uic.cs553.distributed.simcore.MessageType.CONTROL,
          s"WAVEACK:$id:${ctx.nodeId}")

  private def handleAck(ctx: NodeContext, id: String, from: Int): Unit =
    if id != waveId then return  // stale ack from old wave

    pendingAcks -= 1
    logger.info(s"Node ${ctx.nodeId}: got ack from $from, pending=$pendingAcks")

    if pendingAcks == 0 then
      phase match
        case Phase.ACTIVE if parent.isEmpty =>
          // Initiator — wave complete!
          val duration = System.currentTimeMillis() - startTime
          logger.info(s"Node ${ctx.nodeId}: WAVE COMPLETE! waveId=$waveId, duration=${duration}ms")
          phase = Phase.DONE

        case Phase.ACTIVE =>
          // Internal node — propagate ack to parent
          parent.foreach: p =>
            ctx.send(p, com.uic.cs553.distributed.simcore.MessageType.CONTROL,
              s"WAVEACK:$waveId:${ctx.nodeId}")
          phase = Phase.DONE

        case _ => ()