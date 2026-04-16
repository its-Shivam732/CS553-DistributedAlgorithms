package com.uic.cs553.distributed.simcli

import akka.actor.ActorRef
import com.uic.cs553.distributed.simruntimeakka.NodeActor
import org.slf4j.LoggerFactory
import scala.io.Source
import scala.concurrent.{ExecutionContext, Future}

/**
 * File-driven message injector.
 *
 * Reads an injection script file where each line specifies:
 *   - when to inject (delayMs from simulation start)
 *   - which node to inject into
 *   - what message type and payload
 *
 * This models external clients, sensors, or test harnesses
 * injecting messages into input nodes at controlled times.
 *
 * Example injection file (inject.txt):
 *   # Inject WORK into node 1 after 100ms
 *   delayMs=100,node=1,kind=WORK,payload=task-001
 *   delayMs=200,node=2,kind=PING,payload=probe
 *   delayMs=500,node=1,kind=GOSSIP,payload=update
 */
object FileDrivenInjector:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Load and execute an injection script.
   * Each entry is scheduled as a delayed Future.
   *
   * @param scriptPath path to the injection script file
   * @param nodeRefs   map of nodeId → ActorRef
   * @param ec         execution context for scheduling
   */
  def run(
           scriptPath: String,
           nodeRefs:   Map[Int, ActorRef]
         )(using ec: ExecutionContext): Unit =
    logger.info(s"Loading injection script: $scriptPath")

    val entries = loadScript(scriptPath)
    logger.info(s"Loaded ${entries.size} injection entries")

    entries.foreach: entry =>
      Future:
        Thread.sleep(entry.delayMs)
        inject(nodeRefs, entry)

  /**
   * Load and parse the injection script file.
   * Skips blank lines and comments (lines starting with #).
   */
  def loadScript(path: String): List[InjectionEntry] =
    try
      Source.fromFile(path)
        .getLines()
        .flatMap(InjectionEntry.parse)
        .toList
        .sortBy(_.delayMs)  // sort by delay so we process in order
    catch
      case e: Exception =>
        logger.error(s"Failed to load injection script: ${e.getMessage}")
        List.empty

  /**
   * Inject a single message into a node.
   * Only works if the node is an input node (configured in application.conf).
   */
  private def inject(
                      nodeRefs: Map[Int, ActorRef],
                      entry:    InjectionEntry
                    ): Unit =
    nodeRefs.get(entry.nodeId) match
      case Some(ref) =>
        ref ! NodeActor.ExternalInput(entry.kind, entry.payload)
        logger.info(
          s"Injected ${entry.kind} into node ${entry.nodeId}: '${entry.payload}'"
        )
      case None =>
        logger.warn(s"No actor found for node ${entry.nodeId}")