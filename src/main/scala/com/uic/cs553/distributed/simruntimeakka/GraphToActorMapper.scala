package com.uic.cs553.distributed.simruntimeakka

import akka.actor.{ActorRef, ActorSystem}
import com.uic.cs553.distributed.simcore.{EnrichedGraph, MessageType, PdfSampler}
import org.slf4j.LoggerFactory

/**
 * Converts an EnrichedGraph into a running Akka actor system.
 *
 * Pipeline:
 *   EnrichedGraph → one NodeActor per node → Init message per actor
 *
 * This is like Kubernetes spinning up one Pod per service
 * and wiring them together via service discovery.
 *
 * Steps:
 * 1. Create one NodeActor per graph node
 * 2. Build neighbor maps (nodeId → ActorRef)
 * 3. Send Init to each actor with its config
 */
object GraphToActorMapper:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Spawn all actors and wire them together.
   * Returns map of nodeId → ActorRef for external use (CLI, algorithms).
   */
  def map(
           graph:      EnrichedGraph,
           system:     ActorSystem,
           config:     com.uic.cs553.distributed.simcore.SimConfig,
           metrics:    MetricsCollector,
           sampler:    PdfSampler,
           algorithms: Map[Int, List[DistributedAlgorithm]] = Map.empty
         ): Map[Int, ActorRef] =

    val nodeIds = graph.graph.nodes.toList.sorted
    logger.info(s"Creating ${nodeIds.size} actors")

    // Step 1 — create one actor per node
    val nodeRefs: Map[Int, ActorRef] =
      nodeIds.map: id =>
        val algs = algorithms.getOrElse(id, List.empty)
        val ref  = system.actorOf(
          NodeActor.props(id, metrics, sampler, algs),
          s"node-$id"
        )
        id -> ref
      .toMap

    logger.info(s"Created ${nodeRefs.size} actors")

    // Step 2 — send Init to each actor with its specific config
    nodeIds.foreach: id =>
      val outgoing = graph.graph.outNeighbors(id)

      // Only know about direct neighbors — like service discovery
      val neighborRefs: Map[Int, ActorRef] =
        outgoing.map(to => to -> nodeRefs(to)).toMap

      // Edge label constraints as MessageType sets
      val allowedOnEdge: Map[Int, Set[MessageType]] =
        outgoing.map: to =>
          to -> graph.allowedOn(id, to)
        .toMap

      val pdf         = graph.pdfFor(id)
      val timerEnabled = config.isTimerNode(id)
      val tickEvery    = config.tickEvery(id)

      nodeRefs(id) ! NodeActor.Init(
        neighbors     = neighborRefs,
        allowedOnEdge = allowedOnEdge,
        pdf           = pdf,
        timerEnabled  = timerEnabled,
        tickEvery     = tickEvery
      )

      logger.info(s"Initialized node $id: ${neighborRefs.size} neighbors, timer=$timerEnabled")

    nodeRefs