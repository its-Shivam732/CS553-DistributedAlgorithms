package com.uic.cs553.distributed.algorithms

/**
 * Messages used exclusively by the Lai-Yang snapshot algorithm.
 * These are NOT NodeActor.Msg — they are algorithm-level messages
 * passed via the NodeContext as CONTROL payloads.
 */
object LaiYangMessages:

  enum Color:
    case WHITE, RED

  sealed trait LaiYangMsg

  final case class ColoredMessage(
                                   from:    Int,
                                   payload: String,
                                   color:   Color
                                 ) extends LaiYangMsg

  final case class SnapshotMarker(
                                   snapshotId: String,
                                   from:       Int
                                 ) extends LaiYangMsg

  final case class LocalSnapshot(
                                  snapshotId:       String,
                                  nodeId:           Int,
                                  localState:       Map[String, String],
                                  inFlightMessages: List[String]
                                ) extends LaiYangMsg

  final case class SnapshotComplete(
                                     snapshotId: String,
                                     allStates:  Map[Int, LocalSnapshot]
                                   ) extends LaiYangMsg