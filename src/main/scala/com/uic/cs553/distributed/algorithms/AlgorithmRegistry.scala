package com.uic.cs553.distributed.algorithms

import com.uic.cs553.distributed.simruntimeakka.DistributedAlgorithm
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/**
 * Config-driven algorithm loader.
 *
 * Reads which algorithms to run from application.conf and
 * returns a map of nodeId → List[DistributedAlgorithm].
 *
 * This means you can switch algorithms WITHOUT changing code —
 * just change the config file.
 *
 * Config example:
 * sim {
 *   algorithms {
 *     enabled = ["WaveAlgorithm", "LaiYangSnapshot"]
 *     initiatorNode = 0
 *   }
 * }
 */
object AlgorithmRegistry:
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Build algorithm instances for all nodes.
   * Initiator node gets isInitiator=true for each algorithm.
   *
   * @param nodeIds      all node IDs in the graph
   * @param config       simulation config
   * @return map of nodeId → algorithms to run on that node
   */
  def build(
             nodeIds:      Set[Int],
             initiatorId:  Int = 0,
             enableWave:   Boolean = true,
             enableLaiYang: Boolean = true
           ): Map[Int, List[DistributedAlgorithm]] =
    nodeIds.map: nodeId =>
      val isInit = nodeId == initiatorId
      val algs   = scala.collection.mutable.ListBuffer[DistributedAlgorithm]()

      if enableWave then
        algs += WaveAlgorithm(isInitiator = isInit)
        logger.info(s"Node $nodeId: WaveAlgorithm enabled (initiator=$isInit)")

      if enableLaiYang then
        algs += LaiYangSnapshot(isInitiator = isInit)
        logger.info(s"Node $nodeId: LaiYangSnapshot enabled (initiator=$isInit)")

      nodeId -> algs.toList
    .toMap