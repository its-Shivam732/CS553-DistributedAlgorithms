package com.uic.cs553.distributed.simcli

import com.uic.cs553.distributed.simcore.MessageType

/**
 * Data model for the file-driven injection script.
 *
 * Each line in the injection file describes one message to inject:
 *   delayMs=100,node=1,kind=WORK,payload=hello
 *   delayMs=200,node=2,kind=PING,payload=probe
 *
 * This models external clients/sensors injecting messages
 * into input nodes at specific times during the simulation.
 */
case class InjectionEntry(
                           delayMs: Long,
                           nodeId:  Int,
                           kind:    MessageType,
                           payload: String
                         )

object InjectionEntry:
  /**
   * Parse one line of an injection script file.
   * Format: delayMs=100,node=1,kind=WORK,payload=hello
   * Returns None if line is malformed or a comment.
   */
  def parse(line: String): Option[InjectionEntry] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then
      None
    else
      try
        val parts = trimmed.split(",").map(_.trim)
        val fields = parts.map: part =>
          val kv = part.split("=", 2)
          kv(0).trim -> kv(1).trim
        .toMap

        Some(InjectionEntry(
          delayMs = fields("delayMs").toLong,
          nodeId  = fields("node").toInt,
          kind    = MessageType.fromString(fields("kind")),
          payload = fields.getOrElse("payload", "")
        ))
      catch
        case e: Exception =>
          None  // skip malformed lines silently