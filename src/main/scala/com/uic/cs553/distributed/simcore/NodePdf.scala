package com.uic.cs553.distributed.simcore

/**
 * Probability distribution for message generation at a node.
 *
 * Each node has a PDF (Probability Distribution Function) that
 * determines what kind of messages it generates.
 *
 * This is like a traffic profile for a microservice:
 *   Node_0: 50% PING, 30% GOSSIP, 20% WORK
 *   Node_12: 80% WORK, 20% PING
 *
 * Probabilities must sum to 1.0 (validated on construction).
 *
 * @param weights map of MessageType → probability (0.0 to 1.0)
 */
case class NodePdf(weights: Map[MessageType, Double]):
  require(
    math.abs(weights.values.sum - 1.0) < 0.001,
    s"PDF probabilities must sum to 1.0, got ${weights.values.sum}"
  )

  /** All message types this node can produce */
  def messageTypes: Set[MessageType] = weights.keySet

  /** Probability of producing a specific message type */
  def probabilityOf(kind: MessageType): Double =
    weights.getOrElse(kind, 0.0)

  override def toString: String =
    weights.map((k, v) => s"$k:$v").mkString("NodePdf(", ", ", ")")

object NodePdf:
  /**
   * Uniform distribution — equal probability for all message types.
   * Used as default when no per-node override is configured.
   */
  val uniform: NodePdf =
    val types = MessageType.values.toList
    val p = 1.0 / types.size
    NodePdf(types.map(_ -> p).toMap)

  /**
   * Zipf distribution — probability proportional to 1/rank.
   * Most common in real traffic patterns — a few types dominate.
   * rank 1 gets highest probability, rank N gets lowest.
   *
   * @param types ordered list of message types (most to least common)
   */
  def zipf(types: List[MessageType]): NodePdf =
    val harmonicSum = types.indices.map(i => 1.0 / (i + 1)).sum
    val weights = types.zipWithIndex.map: (t, i) =>
      t -> (1.0 / (i + 1)) / harmonicSum
    NodePdf(weights.toMap)

  /**
   * Build a NodePdf from explicit probability pairs.
   * Validates that probabilities sum to 1.0.
   */
  def fromWeights(pairs: (MessageType, Double)*): NodePdf =
    NodePdf(pairs.toMap)