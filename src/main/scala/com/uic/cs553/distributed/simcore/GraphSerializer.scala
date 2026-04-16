package com.uic.cs553.distributed.simcore

import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*
import org.slf4j.LoggerFactory
import java.io.{File, PrintWriter}
import scala.io.Source

/**
 * Serializes and deserializes EnrichedGraph to/from JSON.
 *
 * Required by spec: "edge labels reflected in serialized output"
 *
 * This lets you:
 *   1. Generate graph with NetGameSim
 *   2. Enrich it with labels and PDFs
 *   3. Save enriched version as JSON
 *   4. Load it back without re-running NetGameSim
 */
object GraphSerializer:
  private val logger = LoggerFactory.getLogger(getClass)

  // ── JSON data transfer objects ──────────────────────────────
  // These are simple case classes that circe can auto-encode/decode

  private case class EdgeDto(from: Int, to: Int)
  private case class EdgeLabelDto(
                                   from:    Int,
                                   to:      Int,
                                   allowed: List[String]
                                 )
  private case class PdfEntryDto(msg: String, p: Double)
  private case class NodePdfDto(nodeId: Int, entries: List[PdfEntryDto])
  private case class EnrichedGraphDto(
                                       nodes:      List[Int],
                                       edges:      List[EdgeDto],
                                       edgeLabels: List[EdgeLabelDto],
                                       nodePdfs:   List[NodePdfDto]
                                     )

  // ── Serialization (EnrichedGraph → JSON file) ───────────────

  def save(graph: EnrichedGraph, path: String): Unit =
    logger.info(s"Saving enriched graph to: $path")

    val dto = toDto(graph)
    val json = dto.asJson.spaces2

    val file = File(path)
    file.getParentFile.mkdirs()
    val writer = PrintWriter(file)
    try writer.write(json)
    finally writer.close()

    logger.info(s"Saved enriched graph to: $path")

  // ── Deserialization (JSON file → EnrichedGraph) ─────────────

  def load(path: String): Either[String, EnrichedGraph] =
    logger.info(s"Loading enriched graph from: $path")
    try
      val json = Source.fromFile(path).mkString
      decode[EnrichedGraphDto](json) match
        case Right(dto) =>
          val graph = fromDto(dto)
          logger.info(s"Loaded enriched graph: ${graph.graph}")
          Right(graph)
        case Left(err) =>
          Left(s"Failed to parse graph JSON: $err")
    catch
      case e: Exception =>
        Left(s"Failed to read file $path: ${e.getMessage}")

  // ── DTO conversion helpers ───────────────────────────────────

  private def toDto(g: EnrichedGraph): EnrichedGraphDto =
    EnrichedGraphDto(
      nodes = g.graph.nodes.toList.sorted,
      edges = g.graph.edges.toList.map(e => EdgeDto(e.from, e.to)),
      edgeLabels = g.edgeLabels.values.toList.map: el =>
        EdgeLabelDto(
          from    = el.from,
          to      = el.to,
          allowed = el.allowedTypes.map(_.toString).toList
        ),
      nodePdfs = g.nodePdfs.toList.map: (nodeId, pdf) =>
        NodePdfDto(
          nodeId  = nodeId,
          entries = pdf.weights.toList.map: (t, p) =>
            PdfEntryDto(t.toString, p)
        )
    )

  private def fromDto(dto: EnrichedGraphDto): EnrichedGraph =
    val graph = SimGraph(
      nodes = dto.nodes.toSet,
      edges = dto.edges.map(e => SimEdge(e.from, e.to)).toSet
    )
    val edgeLabels = dto.edgeLabels.map: el =>
      (el.from, el.to) -> EdgeLabel(
        from         = el.from,
        to           = el.to,
        allowedTypes = el.allowed.map(MessageType.fromString).toSet
      )
    .toMap
    val nodePdfs = dto.nodePdfs.map: np =>
      np.nodeId -> NodePdf(
        np.entries.map(e =>
          MessageType.fromString(e.msg) -> e.p
        ).toMap
      )
    .toMap
    EnrichedGraph(graph, edgeLabels, nodePdfs)