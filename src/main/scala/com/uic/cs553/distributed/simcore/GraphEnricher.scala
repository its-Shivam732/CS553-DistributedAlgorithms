package com.uic.cs553.distributed.simcore

import org.slf4j.LoggerFactory

/**
 * Enriches a raw SimGraph with EdgeLabels and NodePdfs.
 *
 * This is the "service mesh configuration" step:
 *   - Assigns routing rules (EdgeLabels) to each edge
 *   - Assigns traffic profiles (NodePdfs) to each node
 *
 * Rules come from SimConfig (application.conf).
 * Edge overrides take priority over defaults.
 * Node PDF overrides take priority over defaults.
 */
object GraphEnricher:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Enrich a SimGraph using rules from SimConfig.
   * Returns an EnrichedGraph ready for actor creation.
   */
  def enrich(graph: SimGraph, config: SimConfig): EnrichedGraph =
    logger.info(s"Enriching graph: $graph")

    val edgeLabels = buildEdgeLabels(graph, config)
    val nodePdfs   = buildNodePdfs(graph, config)

    logger.info(s"Assigned ${edgeLabels.size} edge labels")
    logger.info(s"Assigned ${nodePdfs.size} node PDFs")

    EnrichedGraph(graph, edgeLabels, nodePdfs)

  /**
   * Build EdgeLabel for every edge in the graph.
   * Uses override if one exists in config, otherwise uses default.
   */
  private def buildEdgeLabels(
                               graph:  SimGraph,
                               config: SimConfig
                             ): Map[(Int, Int), EdgeLabel] =
    graph.edges.map: edge =>
      val key = (edge.from, edge.to)

      // Check if config has a specific override for this edge
      val allowedTypes = config.edgeLabelOverrides
        .getOrElse(key, config.defaultEdgeLabel.toSet)

      // CONTROL is always allowed — algorithm messages must pass through
      val finalTypes = allowedTypes + MessageType.CONTROL

      key -> EdgeLabel(edge.from, edge.to, finalTypes)
    .toMap

  /**
   * Build NodePdf for every node in the graph.
   * Uses per-node override if configured, otherwise uses default.
   */
  private def buildNodePdfs(
                             graph:  SimGraph,
                             config: SimConfig
                           ): Map[Int, NodePdf] =
    graph.nodes.map: nodeId =>
      nodeId -> config.pdfForNode(nodeId)
    .toMap