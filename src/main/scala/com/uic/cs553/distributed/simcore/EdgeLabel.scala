package com.uic.cs553.distributed.simcore

/**
 * Defines which message types are allowed to travel on a specific edge.
 *
 * This is exactly like an API Gateway routing rule:
 *   Edge 0→1 allows only [PING, CONTROL]
 *   Edge 1→3 allows only [WORK, GOSSIP, CONTROL]
 *
 * CONTROL is always allowed — it carries algorithm messages.
 * Application traffic (PING, WORK, GOSSIP) is constrained per edge.
 *
 * @param from         source node ID
 * @param to           destination node ID
 * @param allowedTypes set of message types permitted on this channel
 */
case class EdgeLabel(
                      from:         Int,
                      to:           Int,
                      allowedTypes: Set[MessageType]
                    ):
  /**
   * Check if a message type is allowed on this edge.
   * Used by NodeActor before sending any message.
   */
  def allows(kind: MessageType): Boolean =
    allowedTypes.contains(kind)

  override def toString: String =
    s"EdgeLabel($from→$to, allowed=${allowedTypes.mkString(",")})"

object EdgeLabel:
  /**
   * Default edge label — allows CONTROL and PING.
   * Applied to all edges that have no specific override in config.
   */
  def default(from: Int, to: Int): EdgeLabel =
    EdgeLabel(from, to, Set(MessageType.CONTROL, MessageType.PING))

  /**
   * Permissive edge label — allows ALL message types.
   * Useful for testing or when no restrictions needed.
   */
  def allowAll(from: Int, to: Int): EdgeLabel =
    EdgeLabel(from, to, MessageType.values.toSet)