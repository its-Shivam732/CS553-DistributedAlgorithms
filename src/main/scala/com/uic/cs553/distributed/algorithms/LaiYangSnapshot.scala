package com.uic.cs553.distributed.algorithms

import com.uic.cs553.distributed.simruntimeakka.{DistributedAlgorithm, NodeContext}
import com.uic.cs553.distributed.simcore.MessageType
import LaiYangMessages.*
import LaiYangMessages.Color.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Lai-Yang Snapshot Algorithm Implementation.
 *
 * Takes a consistent global snapshot with NON-FIFO channels.
 * Uses RED/WHITE coloring instead of channel markers.
 *
 * System assumptions:
 * - NON-FIFO asynchronous channels
 * - Messages carry color of sender
 * - No process failures during snapshot
 * - General graph topology
 */
class LaiYangSnapshot(val isInitiator: Boolean = false)
  extends DistributedAlgorithm:

  override val name: String = "LaiYangSnapshot"
  private val logger = LoggerFactory.getLogger(s"$name")

  // ── Immutable state model ────────────────────────────────────
  private case class SnapState(
                                color:             Color               = WHITE,
                                snapshotId:        String              = "",
                                localState:        Map[String, String] = Map.empty,
                                inFlightMessages:  List[String]        = List.empty,
                                pendingRed:        Int                 = 0,
                                messagesSentWhite: Map[Int, Int]       = Map.empty,
                                messagesRecvWhite: Map[Int, Int]       = Map.empty,
                                snapshotDone:      Boolean             = false
                              )

  // Single mutable reference to immutable state record
  // justified: owned by one actor, single-threaded access
  private var state: SnapState = SnapState()

  override def onStart(ctx: NodeContext): Unit =
    // Initialize per-channel counters
    val counters = ctx.neighbors.map(_ -> 0).toMap
    state = state.copy(
      messagesSentWhite = counters,
      messagesRecvWhite = counters
    )

    if isInitiator then
      logger.info(s"Node ${ctx.nodeId}: initiating snapshot")
      initiateSnapshot(ctx)

  override def onMessage(ctx: NodeContext, msg: Any): Unit =
    msg match
      case env: com.uic.cs553.distributed.simruntimeakka.NodeActor.Envelope =>
        val payload = env.payload
        if payload.startsWith("SNAPSHOT_MARKER:") then
          val parts = payload.split(":")
          handleMarker(ctx, id = parts(1), from = parts(2).toInt)
        else if state.color == RED then
          // WHITE message arriving at RED node = in-flight, capture it
          val newInFlight = s"from=${env.from} payload=$payload" :: state.inFlightMessages
          logger.info(s"Node ${ctx.nodeId}: captured in-flight message from ${env.from}")
          state = state.copy(inFlightMessages = newInFlight)
        else
          // Track white messages received per channel
          val updated = state.messagesRecvWhite.updatedWith(env.from)(
            _.map(_ + 1).orElse(Some(1))
          )
          state = state.copy(messagesRecvWhite = updated)
      case _ => ()

  override def onTick(ctx: NodeContext): Unit =
    if state.color == RED && !state.snapshotDone then
      checkSnapshotComplete(ctx)

  // ── Private handlers ────────────────────────────────────────

  private def initiateSnapshot(ctx: NodeContext): Unit =
    val snapId = UUID.randomUUID().toString.take(8)
    state = state.copy(snapshotId = snapId)
    recordLocalState(ctx)
    turnRed(ctx)

  private def recordLocalState(ctx: NodeContext): Unit =
    val captured = Map(
      "nodeId"             -> ctx.nodeId.toString,
      "color"              -> state.color.toString,
      "neighbors"          -> ctx.neighbors.mkString(","),
      "snapshotId"         -> state.snapshotId,
      "timestampMs"        -> System.currentTimeMillis().toString,
      "messagesSentWhite"  -> state.messagesSentWhite.values.sum.toString
    )
    logger.info(s"Node ${ctx.nodeId}: recorded local state at snapshot ${state.snapshotId}")
    state = state.copy(localState = captured)

  private def turnRed(ctx: NodeContext): Unit =
    state = state.copy(
      color      = RED,
      pendingRed = ctx.neighbors.size
    )
    logger.info(s"Node ${ctx.nodeId}: turned RED, sending markers to ${ctx.neighbors}")
    ctx.neighbors.foreach: to =>
      ctx.send(to, MessageType.CONTROL,
        s"SNAPSHOT_MARKER:${state.snapshotId}:${ctx.nodeId}")

  private def handleMarker(ctx: NodeContext, id: String, from: Int): Unit =
    state = state.copy(snapshotId = id)

    if state.color == WHITE then
      logger.info(s"Node ${ctx.nodeId}: received first marker from $from, taking snapshot")
      recordLocalState(ctx)
      turnRed(ctx)

    val newPending = math.max(0, state.pendingRed - 1)
    state = state.copy(pendingRed = newPending)
    logger.info(s"Node ${ctx.nodeId}: marker from $from, pendingRed=$newPending")
    checkSnapshotComplete(ctx)

  private def checkSnapshotComplete(ctx: NodeContext): Unit =
    if !state.snapshotDone && state.color == RED && state.pendingRed == 0 then
      state = state.copy(snapshotDone = true)
      logger.info(
        s"Node ${ctx.nodeId}: snapshot COMPLETE. " +
          s"snapshotId=${state.snapshotId} " +
          s"State=${state.localState} " +
          s"InFlight=${state.inFlightMessages.size} messages"
      )