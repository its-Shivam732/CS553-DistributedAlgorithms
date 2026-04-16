package com.uic.cs553.distributed.simruntimeakka

/**
 * Plugin interface for distributed algorithms.
 *
 * Every algorithm implements this trait and gets plugged into
 * NodeActor. The actor calls these methods at the right times.
 *
 * onStart   → called once when node initializes
 * onMessage → called for every message the node receives
 * onTick    → called on every timer tick (optional)
 *
 * NodeContext gives algorithms access to:
 * - send/broadcast to neighbors
 * - node ID and neighbor set
 * - metrics recording
 * - logging
 */
trait DistributedAlgorithm:
  /** Unique name — used to route AlgorithmMsg to correct algorithm */
  def name: String

  /** Called once when the node actor starts up */
  def onStart(ctx: NodeContext): Unit

  /** Called for every message the node receives */
  def onMessage(ctx: NodeContext, msg: Any): Unit

  /** Called on every timer tick — optional, default is no-op */
  def onTick(ctx: NodeContext): Unit = ()