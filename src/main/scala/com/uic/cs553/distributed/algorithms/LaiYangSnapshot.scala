package com.uic.cs553.distributed.algorithms

import com.uic.cs553.distributed.simruntimeakka.{
  DistributedAlgorithm, NodeContext
}
import LaiYangMessages.*
import LaiYangMessages.Color.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Lai-Yang Snapshot Algorithm Implementation.
 *
 * Takes a consistent global snapshot of a distributed system
 * with NON-FIFO channels — unlike Chandy-Lamport which requires FIFO.
 *
 * Key insight: use COLORING (white/red) instead of markers on channels.
 * Every message carries the sender's color when sent.
 * This lets receivers determine which messages are "pre-snapshot"
 * and which are "post-snapshot" without needing FIFO ordering.
 *
 * Algorithm:
 * 1. Initiator turns RED, records local state
 * 2. Sends SnapshotMarker to all neighbors
 * 3. Any WHITE node receiving a marker turns RED and records state
 * 4. WHITE messages arriving at RED nodes = in-flight, captured
 * 5. When all nodes RED and all channels drained = snapshot complete
 *
 * System assumptions (required by course spec):
 * - NON-FIFO asynchronous channels (stronger than Chandy-Lamport)
 * - Messages carry color of sender
 * - No process failures during snapshot
 * - General graph topology
 *
 * @param isInitiator true for the node that triggers the snapshot
 */
class LaiYangSnapshot(isInitiator: Boolean = false)
  extends DistributedAlgorithm:

  override val name: String = "LaiYangSnapshot"
  private val logger = LoggerFactory.getLogger(s"$name")

  // ── Algorithm state (per node) ───────────────────────────────
  // justified: single-threaded actor access, no sharing
  private var color:            Color            = WHITE
  private var snapshotId:       String           = ""
  private var localState:       Map[String, String] = Map.empty
  private var inFlightMessages: List[String]     = List.empty
  private var pendingRed:       Int              = 0  // neighbors still WHITE
  private var messagesSentWhite: Map[Int, Int]   = Map.empty  // channel → count
  private var messagesRecvWhite: Map[Int, Int]   = Map.empty  // channel → count
  private var snapshotDone:     Boolean          = false

  override def onStart(ctx: NodeContext): Unit =
    // Initialize message counters for all channels
    messagesSentWhite = ctx.neighbors.map(_ -> 0).toMap
    messagesRecvWhite = ctx.neighbors.map(_ -> 0).toMap

    if isInitiator then
      logger.info(s"Node ${ctx.nodeId}: initiating snapshot")
      // Small delay to let background traffic build up
      // so there are in-flight messages to capture
      initiateSnapshot(ctx)

  override def onMessage(ctx: NodeContext, msg: Any): Unit =
    msg match
      case SnapshotMarker(id, from) =>
        handleMarker(ctx, id, from)

      case ColoredMessage(from, payload, senderColor) =>
        handleColoredMessage(ctx, from, payload, senderColor)

      case _ =>
        // Track white messages on channels for in-flight detection
        if color == WHITE then
          messagesRecvWhite = messagesRecvWhite.updatedWith(
            // extract from ID from payload if possible
            -1
          )(_.map(_ + 1).orElse(Some(1)))

  override def onTick(ctx: NodeContext): Unit =
    // Periodically check if snapshot is complete
    if color == RED && !snapshotDone then
      checkSnapshotComplete(ctx)

  // ── Private handlers ────────────────────────────────────────

  private def initiateSnapshot(ctx: NodeContext): Unit =
    snapshotId = UUID.randomUUID().toString.take(8)
    recordLocalState(ctx)
    turnRed(ctx)

  private def recordLocalState(ctx: NodeContext): Unit =
    // Capture this node's current state
    localState = Map(
      "nodeId"          -> ctx.nodeId.toString,
      "color"           -> color.toString,
      "neighbors"       -> ctx.neighbors.mkString(","),
      "snapshotId"      -> snapshotId,
      "timestampMs"     -> System.currentTimeMillis().toString,
      "messagesSentWhite" -> messagesSentWhite.values.sum.toString
    )
    logger.info(s"Node ${ctx.nodeId}: recorded local state at snapshot $snapshotId")

  private def turnRed(ctx: NodeContext): Unit =
    color = RED
    pendingRed = ctx.neighbors.size
    logger.info(s"Node ${ctx.nodeId}: turned RED, sending markers to ${ctx.neighbors}")

    // Send snapshot marker to all neighbors
    ctx.neighbors.foreach: to =>
      ctx.send(
        to,
        com.uic.cs553.distributed.simcore.MessageType.CONTROL,
        s"SNAPSHOT_MARKER:$snapshotId:${ctx.nodeId}"
      )

  private def handleMarker(ctx: NodeContext, id: String, from: Int): Unit =
    snapshotId = id
    if color == WHITE then
      // First marker received — take snapshot and turn red
      logger.info(s"Node ${ctx.nodeId}: received first marker from $from, taking snapshot")
      recordLocalState(ctx)
      turnRed(ctx)

    // Record that this neighbor is now RED
    pendingRed = math.max(0, pendingRed - 1)
    logger.info(s"Node ${ctx.nodeId}: marker from $from, pendingRed=$pendingRed")
    checkSnapshotComplete(ctx)

  private def handleColoredMessage(
                                    ctx:         NodeContext,
                                    from:        Int,
                                    payload:     String,
                                    senderColor: Color
                                  ): Unit =
    // Track received white messages per channel
    if senderColor == WHITE then
      messagesRecvWhite = messagesRecvWhite.updatedWith(from)(
        _.map(_ + 1).orElse(Some(1))
      )

    // If WE are RED and message is WHITE → it's an in-flight message
    // Must capture it as part of channel state
    if color == RED && senderColor == WHITE then
      inFlightMessages = s"from=$from payload=$payload" :: inFlightMessages
      logger.info(s"Node ${ctx.nodeId}: captured in-flight message from $from")

  private def checkSnapshotComplete(ctx: NodeContext): Unit =
    if !snapshotDone && color == RED && pendingRed == 0 then
      snapshotDone = true
      val snapshot = LocalSnapshot(
        snapshotId       = snapshotId,
        nodeId           = ctx.nodeId,
        localState       = localState,
        inFlightMessages = inFlightMessages
      )
      logger.info(
        s"Node ${ctx.nodeId}: snapshot COMPLETE. " +
          s"State=$localState " +
          s"InFlight=${inFlightMessages.size} messages"
      )