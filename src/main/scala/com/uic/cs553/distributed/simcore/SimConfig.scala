package com.uic.cs553.distributed.simcore

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*

class SimConfig(config: Config = ConfigFactory.load()):
  private val sim = config.getConfig("sim")

  val graphFile: String = sim.getString("graphFile")
  val seed: Long = sim.getLong("seed")
  val runDuration: FiniteDuration =
    sim.getInt("runDurationSeconds").seconds

  val messageTypes: List[MessageType] =
    sim.getStringList("messages.types")
      .asScala
      .map(MessageType.fromString)
      .toList

  val defaultEdgeLabel: List[MessageType] =
    sim.getStringList("edgeLabeling.default")
      .asScala
      .map(MessageType.fromString)
      .toList

  val edgeLabelOverrides: Map[(Int, Int), Set[MessageType]] =
    sim.getConfigList("edgeLabeling.overrides")
      .asScala
      .map: c =>
        val from  = c.getInt("from")
        val to    = c.getInt("to")
        val types = c.getStringList("allow")
          .asScala
          .map(MessageType.fromString)
          .toSet
        (from, to) -> types
      .toMap

  val tickIntervalMs: Int = sim.getInt("traffic.tickIntervalMs")

  val defaultPdf: NodePdf =
    val entries = sim.getConfigList("traffic.defaultPdf")
      .asScala
      .map: c =>
        MessageType.fromString(c.getString("msg")) ->
          c.getDouble("p")
      .toMap
    NodePdf(entries)

  val perNodePdfs: Map[Int, NodePdf] =
    sim.getConfigList("traffic.perNodePdf")
      .asScala
      .map: c =>
        val nodeId = c.getInt("node")
        val entries = c.getConfigList("pdf")
          .asScala
          .map: p =>
            MessageType.fromString(p.getString("msg")) ->
              p.getDouble("p")
          .toMap
        nodeId -> NodePdf(entries)
      .toMap

  def pdfForNode(nodeId: Int): NodePdf =
    perNodePdfs.getOrElse(nodeId, defaultPdf)

  case class TimerConfig(
                          nodeId:    Int,
                          tickEvery: FiniteDuration,
                          mode:      String,
                          fixedMsg:  Option[MessageType]
                        )

  val timerNodes: List[TimerConfig] =
    sim.getConfigList("initiators.timers")
      .asScala
      .map: c =>
        TimerConfig(
          nodeId    = c.getInt("node"),
          tickEvery = c.getInt("tickEveryMs").millis,
          mode      = c.getString("mode"),
          fixedMsg  = if c.getString("mode") == "fixed"
          then Some(MessageType.fromString(c.getString("fixedMsg")))
          else None
        )
      .toList

  def isTimerNode(nodeId: Int): Boolean =
    timerNodes.exists(_.nodeId == nodeId)

  def tickEvery(nodeId: Int): FiniteDuration =
    timerNodes
      .find(_.nodeId == nodeId)
      .map(_.tickEvery)
      .getOrElse(tickIntervalMs.millis)

  val inputNodes: List[Int] =
    sim.getConfigList("initiators.inputs")
      .asScala
      .map(_.getInt("node"))
      .toList

  def isInputNode(nodeId: Int): Boolean =
    inputNodes.contains(nodeId)

  def withOverrides(
                     graphFile: Option[String] = None,
                     runSecs: Option[Int] = None
                   ): SimConfig =
    val cfg1 = graphFile.fold(config)(g =>
      config.withValue("sim.graphFile", ConfigValueFactory.fromAnyRef(g))
    )
    val cfg2 = runSecs.fold(cfg1)(s =>
      cfg1.withValue("sim.runDurationSeconds", ConfigValueFactory.fromAnyRef(s))
    )
    new SimConfig(cfg2)


object SimConfig:
  def apply(): SimConfig =
    new SimConfig(ConfigFactory.load())

  def fromFile(path: String): SimConfig =
    import java.io.File
    val file = File(path)
    val cfg  = if file.isAbsolute || file.exists()
    then ConfigFactory.parseFile(file).withFallback(ConfigFactory.load())
    else ConfigFactory.load(path)
    new SimConfig(cfg)