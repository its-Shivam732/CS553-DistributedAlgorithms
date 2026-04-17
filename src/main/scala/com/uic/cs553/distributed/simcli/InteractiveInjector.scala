package com.uic.cs553.distributed.simcli

import akka.actor.ActorRef
import com.uic.cs553.distributed.simcore.MessageType
import com.uic.cs553.distributed.simruntimeakka.NodeActor
import org.slf4j.LoggerFactory
import scala.io.StdIn

/**
 * Interactive message injector.
 *
 * Reads commands from standard input and forwards messages
 * to target node actors in real time.
 *
 * Command format:
 *   node=<id> kind=<TYPE> payload=<text>
 *
 * Examples:
 *   node=1 kind=WORK payload=process-this
 *   node=2 kind=PING payload=hello
 *   quit
 *
 * This models an operator manually injecting messages during
 * a live simulation run — like a debug console.
 */
object InteractiveInjector:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Start the interactive injection loop.
   * Blocks the calling thread reading from stdin.
   * Type "quit" or "exit" to stop.
   *
   * @param nodeRefs map of nodeId → ActorRef
   */
  def run(nodeRefs: Map[Int, ActorRef]): Unit =
    println("\n=== Interactive Injection Mode ===")
    println("Commands: node=<id> kind=<TYPE> payload=<text>")
    println("Message types: CONTROL, PING, GOSSIP, WORK, ACK")
    println("Type 'quit' to exit")
    println("Type 'nodes' to list available nodes")
    println("==================================\n")

    @annotation.tailrec
    def loop(): Unit =
      print("> ")
      val line = StdIn.readLine()
      if line == null || line.trim == "quit" || line.trim == "exit" then
        println("Exiting interactive mode.")
      else
        processCommand(line.trim, nodeRefs)
        loop()

    loop()

  /**
   * Process one command line from stdin.
   */
  private def processCommand(
                              line:     String,
                              nodeRefs: Map[Int, ActorRef]
                            ): Unit =
    if line.isEmpty then ()
    else if line == "nodes" then
      println(s"Available nodes: ${nodeRefs.keys.toList.sorted.mkString(", ")}")
    else if line == "help" then
      printHelp()
    else
      parseCommand(line) match
        case Some(entry) =>
          nodeRefs.get(entry.nodeId) match
            case Some(ref) =>
              ref ! NodeActor.ExternalInput(entry.kind, entry.payload)
              println(s"✓ Injected ${entry.kind} into node ${entry.nodeId}")
              logger.info(s"Interactive: injected ${entry.kind} → node ${entry.nodeId}")
            case None =>
              println(s"✗ No node with id ${entry.nodeId}")
              println(s"  Available: ${nodeRefs.keys.toList.sorted.mkString(", ")}")
        case None =>
          println(s"✗ Could not parse: '$line'")
          println("  Format: node=<id> kind=<TYPE> payload=<text>")

  /**
   * Parse one command line into an InjectionEntry.
   * Format: node=1 kind=WORK payload=hello
   */
  private def parseCommand(line: String): Option[InjectionEntry] =
    try
      val fields = line.split("\\s+").map: part =>
        val kv = part.split("=", 2)
        kv(0).trim -> kv(1).trim
      .toMap

      Some(InjectionEntry(
        delayMs = 0L,
        nodeId  = fields("node").toInt,
        kind    = MessageType.fromString(fields("kind")),
        payload = fields.getOrElse("payload", "")
      ))
    catch
      case _: Exception => None

  private def printHelp(): Unit =
    println("""
              |Commands:
              |  node=<id> kind=<TYPE> payload=<text>  — inject a message
              |  nodes                                  — list available nodes
              |  quit / exit                            — stop injector
              |  help                                   — show this help
              |
              |Message types: CONTROL, PING, GOSSIP, WORK, ACK
              |""".stripMargin)