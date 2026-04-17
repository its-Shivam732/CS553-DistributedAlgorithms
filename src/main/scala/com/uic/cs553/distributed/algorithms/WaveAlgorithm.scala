package com.uic.cs553.distributed.algorithms

import com.uic.cs553.distributed.simruntimeakka.{DistributedAlgorithm, NodeContext}
import com.uic.cs553.distributed.simcore.MessageType
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Wave Algorithm Implementation (Echo Algorithm variant).
 *
 * System assumptions:
 * - Asynchronous message passing
 * - Reliable channels (no message loss)
 * - General graph topology
 * - No process failures
 *
 * State is immutable — transitions modeled as replacing the
 * entire state object rather than mutating fields.
 */
class WaveAlgorithm(val isInitiator: Boolean = false)
  extends DistributedAlgorithm:

  override val name: String = "WaveAlgorithm"
  private val logger = LoggerFactory.getLogger(s"$name")

  // ── Immutable state model ────────────────────────────────────
  enum Phase:
    case IDLE, ACTIVE, DONE

  private case class WaveState(
                                phase:       Phase        = Phase.IDLE,
                                parent:      Option[Int]  = None,
                                pendingAcks: Int          = 0,
                                waveId:      String       = "",
                                startTime:   Long         = 0L
                              )

  // Single mutable reference to immutable state record
  // justified: DistributedAlgorithm lifecycle is owned by one actor
  private var state: WaveState = WaveState()

  override def onStart(ctx: NodeContext): Unit =
    if isInitiator then
      logger.info(s"Node ${ctx.nodeId}: initiating wave")
      startWave(ctx)

  override def onMessage(ctx: NodeContext, msg: Any): Unit =
    msg match
      case env: com.uic.cs553.distributed.simruntimeakka.NodeActor.Envelope =>
        val payload = env.payload
        if payload.startsWith("WAVE:") && !payload.startsWith("WAVEACK:") then
          val parts = payload.split(":")
          handleWave(ctx, id = parts(1), from = parts(2).toInt)
        else if payload.startsWith("WAVEACK:") then
          val parts = payload.split(":")
          handleAck(ctx, id = parts(1), from = parts(2).toInt)
      case _ => ()

  override def onTick(ctx: NodeContext): Unit = ()

  // ── Private handlers ────────────────────────────────────────

  private def startWave(ctx: NodeContext): Unit =
    val waveId    = UUID.randomUUID().toString.take(8)
    val neighbors = ctx.neighbors.toList

    if neighbors.isEmpty then
      logger.info(s"Node ${ctx.nodeId}: wave $waveId complete (no neighbors)")
      state = WaveState(phase = Phase.DONE, waveId = waveId)
    else
      state = WaveState(
        phase       = Phase.ACTIVE,
        parent      = None,
        pendingAcks = neighbors.size,
        waveId      = waveId,
        startTime   = System.currentTimeMillis()
      )
      logger.info(s"Node ${ctx.nodeId}: sending wave $waveId to $neighbors")
      neighbors.foreach: to =>
        ctx.send(to, MessageType.CONTROL, s"WAVE:$waveId:${ctx.nodeId}")

  private def handleWave(ctx: NodeContext, id: String, from: Int): Unit =
    state.phase match
      case Phase.IDLE =>
        val forwardTo = ctx.neighbors.filterNot(_ == from).toList
        logger.info(s"Node ${ctx.nodeId}: received wave $id from $from, forwarding to $forwardTo")

        if forwardTo.isEmpty then
          logger.info(s"Node ${ctx.nodeId}: leaf node, acking parent $from")
          ctx.send(from, MessageType.CONTROL, s"WAVEACK:$id:${ctx.nodeId}")
          state = WaveState(phase = Phase.DONE, waveId = id, parent = Some(from))
        else
          state = WaveState(
            phase       = Phase.ACTIVE,
            parent      = Some(from),
            pendingAcks = forwardTo.size,
            waveId      = id,
            startTime   = System.currentTimeMillis()
          )
          forwardTo.foreach: to =>
            ctx.send(to, MessageType.CONTROL, s"WAVE:$id:${ctx.nodeId}")

      case Phase.ACTIVE | Phase.DONE =>
        ctx.send(from, MessageType.CONTROL, s"WAVEACK:$id:${ctx.nodeId}")

  private def handleAck(ctx: NodeContext, id: String, from: Int): Unit =
    if id != state.waveId then return

    val newPending = state.pendingAcks - 1
    logger.info(s"Node ${ctx.nodeId}: got ack from $from, pending=$newPending")

    if newPending == 0 then
      state.phase match
        case Phase.ACTIVE if state.parent.isEmpty =>
          val duration = System.currentTimeMillis() - state.startTime
          logger.info(s"Node ${ctx.nodeId}: WAVE COMPLETE! waveId=${state.waveId}, duration=${duration}ms")
          state = state.copy(phase = Phase.DONE, pendingAcks = 0)

        case Phase.ACTIVE =>
          state.parent.foreach: p =>
            ctx.send(p, MessageType.CONTROL, s"WAVEACK:${state.waveId}:${ctx.nodeId}")
          state = state.copy(phase = Phase.DONE, pendingAcks = 0)

        case _ => ()
    else
      state = state.copy(pendingAcks = newPending)