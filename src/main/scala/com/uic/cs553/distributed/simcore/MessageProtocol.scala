package com.uic.cs553.distributed.simcore

enum MessageType:
 case CONTROL, PING, GOSSIP, WORK, ACK

object MessageType {
  def fromString(s: String): MessageType = s.toUpperCase match {
    case "CONTROL" => CONTROL
    case "PING"    => PING
    case "GOSSIP"  => GOSSIP
    case "WORK"    => WORK
    case "ACK"     => ACK
    case other     => throw new IllegalArgumentException(s"Unknown message type: $other")
  }
}

case class Envelope(
                     from:    Int,
                     to:      Int,
                     kind:    MessageType,
                     payload: String
                   )