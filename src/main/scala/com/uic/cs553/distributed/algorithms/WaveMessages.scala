package com.uic.cs553.distributed.algorithms

/**
 * Messages used exclusively by the Wave algorithm.
 */
object WaveMessages:

  sealed trait WaveMsg

  final case class Wave(
                         waveId: String,
                         from:   Int
                       ) extends WaveMsg

  final case class WaveAck(
                            waveId: String,
                            from:   Int
                          ) extends WaveMsg

  final case class WaveComplete(
                                 waveId:       String,
                                 visitedNodes: Set[Int],
                                 durationMs:   Long
                               ) extends WaveMsg