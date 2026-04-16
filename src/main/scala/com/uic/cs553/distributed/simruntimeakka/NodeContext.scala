package com.uic.cs553.distributed.simruntimeakka

import akka.actor.ActorRef
import com.uic.cs553.distributed.simcore.MessageType
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Context object passed to distributed algorithm modules.
 *
 * Provides algorithms with everything they need:
 * - Who am I (nodeId)
 * - Who are my neighbors (neighborRefs)
 * - How to send messages (send, broadcast)
 * - How to log (log)
 * - How to record metrics (metrics)
 *
 * This is like a dependency injection container for algorithms.
 * Algorithms never touch ActorRef directly — they use NodeContext.
 */
case class NodeContext(
                        nodeId:       Int,
                        neighborRefs: Map[Int, ActorRef],
                        allowedOnEdge: Map[Int, Set[MessageType]],
                        metrics:      MetricsCollector,
                        log:          Logger = LoggerFactory.getLogger("NodeContext")
                      ):
  /**
   * Send a message to a specific neighbor.
   * Enforces edge label constraints — drops message if not allowed.
   */
  def send(to: Int, kind: MessageType, payload: String): Unit =
    neighborRefs.get(to) match
      case None =>
        log.warn(s"Node $nodeId: no neighbor with id $to")
      case Some(ref) =>
        val allowed = allowedOnEdge.getOrElse(to, Set.empty)
        if allowed.contains(kind) then
          ref ! NodeActor.Envelope(from = nodeId, kind = kind, payload = payload)
          metrics.recordSent(nodeId, to, kind)
        else
          log.warn(s"Node $nodeId: $kind not allowed on edge $nodeId→$to")
          metrics.recordDropped(nodeId, to, kind)

  /**
   * Broadcast a message to ALL neighbors.
   * Each edge is checked individually — some may be dropped.
   */
  def broadcast(kind: MessageType, payload: String): Unit =
    neighborRefs.keys.foreach(to => send(to, kind, payload))

  /**
   * All neighbor node IDs.
   */
  def neighbors: Set[Int] = neighborRefs.keySet