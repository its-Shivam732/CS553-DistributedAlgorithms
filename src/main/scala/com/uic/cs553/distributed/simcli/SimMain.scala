package com.uic.cs553.distributed.simcli

import akka.actor.ActorRef
import com.uic.cs553.distributed.algorithms.AlgorithmRegistry
import com.uic.cs553.distributed.simruntimeakka.ActorSystemBootstrap
import com.uic.cs553.distributed.simcore.{SimConfig, GraphLoader, GraphEnricher, GraphSerializer}
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Main entry point for the simulation.
 *
 * Usage:
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain [options]"
 *
 * Options:
 *   --config <path>      path to config file (default: application.conf)
 *   --graph <path>       override graphFile from config
 *   --run <Ns>           override run duration e.g. --run 30s
 *   --save-graph <path>  save enriched graph with edge labels to JSON file
 *   --inject <path>      path to injection script file
 *   --interactive        enable interactive injection mode
 *   --wave-only          run only Wave algorithm
 *   --snapshot-only      run only Lai-Yang snapshot
 *   --no-algorithms      run simulation without algorithms (traffic only)
 *
 * Examples:
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s --save-graph outputs/small-enriched.json"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/MediumGraph.ngs --run 60s --snapshot-only"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --inject src/main/resources/inject.txt"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --interactive"
 */
object SimMain:
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    logger.info("=== CS553 Distributed Algorithms Simulator ===")
    logger.info(s"Args: ${args.mkString(" ")}")

    val parsedArgs = parseArgs(args)

    val configPath = parsedArgs.getOrElse("--config", "application.conf")
    val baseConfig = SimConfig.fromFile(configPath)

    val config = baseConfig.withOverrides(
      graphFile = parsedArgs.get("--graph"),
      runSecs   = parsedArgs.get("--run").map(_.stripSuffix("s").toInt)
    )

    val enableWave    = !parsedArgs.contains("--snapshot-only") &&
      !parsedArgs.contains("--no-algorithms")
    val enableLaiYang = !parsedArgs.contains("--wave-only") &&
      !parsedArgs.contains("--no-algorithms")

    logger.info(s"Graph: ${config.graphFile}")
    logger.info(s"Duration: ${config.runDuration}")
    logger.info(s"Wave algorithm: $enableWave")
    logger.info(s"Lai-Yang snapshot: $enableLaiYang")

    val nodeRefs = bootSimulation(
      config        = config,
      enableWave    = enableWave,
      enableLaiYang = enableLaiYang,
      saveGraphPath = parsedArgs.get("--save-graph")
    )

    parsedArgs.get("--inject").foreach: scriptPath =>
      logger.info(s"Running file-driven injection from: $scriptPath")
      FileDrivenInjector.run(scriptPath, nodeRefs)

    if parsedArgs.contains("--interactive") then
      logger.info("Starting interactive injection mode")
      InteractiveInjector.run(nodeRefs)

    logger.info("=== SimMain complete ===")

  private def bootSimulation(
                              config: SimConfig,
                              enableWave: Boolean,
                              enableLaiYang: Boolean,
                              saveGraphPath: Option[String] = None
                            ): Map[Int, ActorRef] =

    // Step 1 — load raw graph from .ngs file
    val simGraph = GraphLoader
      .loadFromPath(config.graphFile)
      .getOrElse(throw RuntimeException(s"Cannot load graph: ${config.graphFile}"))

    // Step 2 — enrich with edge labels and node PDFs
    val enrichedGraph = GraphEnricher.enrich(simGraph, config)

    // Step 3 — save enriched graph JSON if --save-graph was passed
    saveGraphPath.foreach: path =>
      logger.info(s"Saving enriched graph with edge labels to: $path")
      GraphSerializer.save(enrichedGraph, path)
      logger.info(s"Edge label visualization saved to: $path")

    // Step 4 — build algorithm instances
    val algorithms = AlgorithmRegistry.build(
      nodeIds = simGraph.nodes,
      initiatorId = 0,
      enableWave = enableWave,
      enableLaiYang = enableLaiYang
    )

    // Step 5 — boot actor system and run
    // config passed explicitly so --graph/--run CLI overrides work correctly
    val (nodeRefs, system, metrics) = ActorSystemBootstrap.boot(
      algorithms = algorithms,
      config = config
    )
    ActorSystemBootstrap.runAndShutdown(system, metrics, config)
    nodeRefs

  private def parseArgs(args: Array[String]): Map[String, String] =
    @annotation.tailrec
    def loop(remaining: List[String], acc: Map[String, String]): Map[String, String] =
      remaining match
        case Nil => acc
        case arg :: rest if arg.startsWith("--") =>
          rest match
            case value :: tail if !value.startsWith("--") =>
              loop(tail, acc + (arg -> value))
            case _ =>
              loop(rest, acc + (arg -> ""))
        case _ :: rest =>
          loop(rest, acc)
    loop(args.toList, Map.empty)