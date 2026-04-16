package com.uic.cs553.distributed.simruntimeakka

import akka.actor.{ActorRef, ActorSystem}
import com.uic.cs553.distributed.simcore.{
  GraphEnricher, GraphLoader, PdfSampler, SimConfig
}
import org.slf4j.LoggerFactory
import scala.concurrent.Await
import scala.concurrent.duration.*

object ActorSystemBootstrap:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Boot the simulation and return node refs + system.
   * Caller is responsible for running duration and shutdown.
   * This allows SimMain to inject messages WHILE simulation runs.
   */
  def boot(
            algorithms: Map[Int, List[DistributedAlgorithm]] = Map.empty
          ): (Map[Int, ActorRef], ActorSystem, MetricsCollector) =

    logger.info("=== Simulation Starting ===")

    val config  = SimConfig()
    val sampler = PdfSampler(config.seed)
    logger.info(s"Config loaded: graph=${config.graphFile}, seed=${config.seed}")

    val graphFile = java.io.File(config.graphFile)
    val simGraph = GraphLoader.load(
      fileName = graphFile.getName,
      dir      = graphFile.getParent + "/"
    ).getOrElse:
      throw RuntimeException(s"Failed to load graph: ${config.graphFile}")

    val enrichedGraph = GraphEnricher.enrich(simGraph, config)
    logger.info(s"Graph enriched: ${enrichedGraph.graph}")

    val system  = ActorSystem("sim")
    val metrics = MetricsCollector()
    logger.info("ActorSystem created")

    val nodeRefs = GraphToActorMapper.map(
      graph      = enrichedGraph,
      system     = system,
      config     = config,
      metrics    = metrics,
      sampler    = sampler,
      algorithms = algorithms
    )

    logger.info(s"${nodeRefs.size} actors running")
    (nodeRefs, system, metrics)

  /**
   * Run simulation for configured duration then shut down.
   */
  def runAndShutdown(
                      system:  ActorSystem,
                      metrics: MetricsCollector,
                      config:  SimConfig
                    ): Unit =
    logger.info(s"Running for ${config.runDuration}")
    Thread.sleep(config.runDuration.toMillis)
    logger.info("=== Simulation Complete ===")
    metrics.report()
    Await.result(system.terminate(), 30.seconds)
    logger.info("=== Shutdown Complete ===")

  def inject(
              nodeRefs: Map[Int, ActorRef],
              nodeId:   Int,
              kind:     com.uic.cs553.distributed.simcore.MessageType,
              payload:  String
            ): Unit =
    nodeRefs.get(nodeId) match
      case Some(ref) =>
        ref ! NodeActor.ExternalInput(kind, payload)
        logger.info(s"Injected $kind into node $nodeId")
      case None =>
        logger.warn(s"No node with id $nodeId")