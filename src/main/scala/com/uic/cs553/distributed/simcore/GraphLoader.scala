package com.uic.cs553.distributed.simcore

import NetGraphAlgebraDefs.{NetGraph, NetGraphComponent, NodeObject, Action}
import org.slf4j.LoggerFactory
import java.io.{FileInputStream, ObjectInputStream, ObjectStreamClass}
import scala.util.Try

object GraphLoader:
  private val logger = LoggerFactory.getLogger(getClass)

  def load(fileName: String, dir: String): Option[SimGraph] =
    val fullPath = s"$dir$fileName"
    logger.info(s"Loading graph from: $fullPath")
    try
      // Use NetGraph's own classloader to resolve NetGameSim classes
      val netGraphClassLoader = classOf[NetGraph].getClassLoader

      val fis = FileInputStream(fullPath)
      val ois = new ObjectInputStream(fis) {
        override def resolveClass(desc: ObjectStreamClass): Class[?] =
          try netGraphClassLoader.loadClass(desc.getName)
          catch case _: ClassNotFoundException =>
            super.resolveClass(desc)
      }

      val components = ois.readObject().asInstanceOf[List[NetGraphComponent]]
      ois.close()
      fis.close()

      val nodes = components.collect { case n: NodeObject => n }
      val edges = components.collect { case e: Action => e }

      logger.info(s"Deserialized ${nodes.size} nodes, ${edges.size} edges")

      val nodeIds  = nodes.map(_.id).toSet
      val simEdges = edges
        .filter(e => nodeIds.contains(e.fromNode.id) && nodeIds.contains(e.toNode.id))
        .map(e => SimEdge(e.fromNode.id, e.toNode.id))
        .toSet

      val graph = SimGraph(nodeIds, simEdges)
      logger.info(s"Loaded: $graph")
      Some(graph)

    catch
      case e: Exception =>
        logger.error(s"Failed to load graph: ${e.getClass.getName}: ${e.getMessage}")
        None

  def loadFromPath(fullPath: String): Option[SimGraph] =
    val file = java.io.File(fullPath).getAbsoluteFile
    load(file.getName, file.getParent + "/")