package com.uic.cs553.distributed.simcore

/**
 * Internal representation of the simulation graph.
 * Each node = one Akka actor (like one microservice instance)
 * Each edge = a directed communication channel between actors
 *
 * This is loaded from NetGameSim's .ngs file and enriched
 * with EdgeLabels and NodePdfs before actors are spawned.
 */
case class SimGraph(
                     nodes: Set[Int],
                     edges: Set[SimEdge]
                   ):
  /** All outgoing neighbor IDs for a given node */
  def outNeighbors(nodeId: Int): Set[Int] =
    edges
      .filter(_.from == nodeId)
      .map(_.to)

  /** All incoming neighbor IDs for a given node */
  def inNeighbors(nodeId: Int): Set[Int] =
    edges
      .filter(_.to == nodeId)
      .map(_.from)

  /** Check if an edge exists between two nodes */
  def hasEdge(from: Int, to: Int): Boolean =
    edges.exists(e => e.from == from && e.to == to)

  /** Total number of nodes */
  def nodeCount: Int = nodes.size

  /** Total number of edges */
  def edgeCount: Int = edges.size

  override def toString: String =
    s"SimGraph(nodes=${nodeCount}, edges=${edgeCount})"

/**
 * A directed edge between two nodes.
 * from → source node ID
 * to   → destination node ID
 *
 * Edge labels (which message types are allowed) are stored
 * separately in EnrichedGraph to keep this model clean.
 */
case class SimEdge(from: Int, to: Int)

/**
 * A SimGraph enriched with EdgeLabels and NodePdfs.
 * This is the complete model that gets converted into Akka actors.
 *
 * Think of this as your "service mesh config" —
 * it defines both the topology AND the routing/traffic rules.
 */
case class EnrichedGraph(
                          graph:      SimGraph,
                          edgeLabels: Map[(Int, Int), EdgeLabel],
                          nodePdfs:   Map[Int, NodePdf]
                        ):
  /** Get allowed message types for a specific edge */
  def allowedOn(from: Int, to: Int): Set[MessageType] =
    edgeLabels
      .get((from, to))
      .map(_.allowedTypes)
      .getOrElse(Set.empty)

  /** Get PDF for a specific node */
  def pdfFor(nodeId: Int): NodePdf =
    nodePdfs.getOrElse(nodeId, NodePdf.uniform)