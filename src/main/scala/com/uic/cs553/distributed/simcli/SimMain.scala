package com.uic.cs553.distributed.simcli

import akka.actor.ActorRef
import com.uic.cs553.distributed.algorithms.AlgorithmRegistry
import com.uic.cs553.distributed.simruntimeakka.ActorSystemBootstrap
import com.uic.cs553.distributed.simcore.SimConfig
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Main entry point for the simulation.
 *
 * Usage:
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain [options]"
 *
 * Options:
 *   --config <path>    path to config file (default: application.conf)
 *   --graph <path>     override graphFile from config
 *   --run <Ns>         override run duration e.g. --run 30s
 *   --inject <path>    path to injection script file
 *   --interactive      enable interactive injection mode
 *   --wave-only        run only Wave algorithm
 *   --snapshot-only    run only Lai-Yang snapshot
 *   --no-algorithms    run simulation without algorithms (traffic only)
 *
 * Examples:
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain"
 *   sbt "runMain com.uic.cs553.distributed.simcli.SimMain --graph src/main/resources/graphs/SmallGraph.ngs --run 30s"
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

    // Load base config — from explicit path or default application.conf
    val configPath = parsedArgs.getOrElse("--config", "application.conf")
    val baseConfig = SimConfig.fromFile(configPath)

    // Apply CLI overrides on top of config file
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

    val nodeRefs = bootSimulation(config, enableWave, enableLaiYang)

    parsedArgs.get("--inject").foreach: scriptPath =>
      logger.info(s"Running file-driven injection from: $scriptPath")
      FileDrivenInjector.run(scriptPath, nodeRefs)

    if parsedArgs.contains("--interactive") then
      logger.info("Starting interactive injection mode")
      InteractiveInjector.run(nodeRefs)

    logger.info("=== SimMain complete ===")

  private def bootSimulation(
                              config:        SimConfig,
                              enableWave:    Boolean,
                              enableLaiYang: Boolean
                            ): Map[Int, ActorRef] =
    val simGraph = com.uic.cs553.distributed.simcore.GraphLoader
      .loadFromPath(config.graphFile)
      .getOrElse(throw RuntimeException(s"Cannot load graph: ${config.graphFile}"))

    val algorithms = AlgorithmRegistry.build(
      nodeIds       = simGraph.nodes,
      initiatorId   = 0,
      enableWave    = enableWave,
      enableLaiYang = enableLaiYang
    )

    val (nodeRefs, system, metrics) = ActorSystemBootstrap.boot(algorithms)

    ActorSystemBootstrap.runAndShutdown(system, metrics, config)

    nodeRefs

  private def parseArgs(args: Array[String]): Map[String, String] =
    val result = scala.collection.mutable.Map[String, String]()
    var i = 0
    while i < args.length do
      val arg = args(i)
      if arg.startsWith("--") then
        if i + 1 < args.length && !args(i + 1).startsWith("--") then
          result(arg) = args(i + 1)
          i += 2
        else
          result(arg) = ""
          i += 1
      else
        i += 1
    result.toMap