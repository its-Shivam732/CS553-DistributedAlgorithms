package com.uic.cs553.distributed.simruntimeakka

import com.uic.cs553.distributed.simcore.MessageType
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

/**
 * Collects simulation metrics across all nodes.
 * Thread-safe — uses atomic counters and concurrent maps.
 * Multiple actors update this concurrently with no locks needed.
 *
 * Like Prometheus metrics but in-memory for this simulation.
 */
class MetricsCollector:
  private val logger = LoggerFactory.getLogger(getClass)

  // (fromNode, toNode, messageType) → count
  private val sentCounts     = TrieMap[(Int, Int, MessageType), AtomicLong]()
  // (nodeId, messageType) → count
  private val receivedCounts = TrieMap[(Int, MessageType), AtomicLong]()
  // (fromNode, messageType) → count
  private val droppedCounts  = TrieMap[(Int, MessageType), AtomicLong]()

  def recordSent(from: Int, to: Int, kind: MessageType): Unit =
    sentCounts
      .getOrElseUpdate((from, to, kind), AtomicLong(0))
      .incrementAndGet()

  def recordReceived(nodeId: Int, kind: MessageType): Unit =
    receivedCounts
      .getOrElseUpdate((nodeId, kind), AtomicLong(0))
      .incrementAndGet()

  def recordDropped(from: Int, to: Int, kind: MessageType): Unit =
    droppedCounts
      .getOrElseUpdate((from, kind), AtomicLong(0))
      .incrementAndGet()

  def totalSent: Long =
    sentCounts.values.map(_.get).sum

  def totalDropped: Long =
    droppedCounts.values.map(_.get).sum

//  def report(): Unit =
//    logger.info("=== Simulation Metrics ===")
//    logger.info(s"Total messages sent:    $totalSent")
//    logger.info(s"Total messages dropped: $totalDropped")
//    sentCounts
//      .groupBy(_._1._3)
//      .foreach: (kind, entries) =>
//        val total = entries.values.map(_.get).sum
//        logger.info(s"  $kind: $total messages")
//    logger.info("==========================")

  def report(): Unit =
    logger.info("==========================================")
    logger.info("=== SIMULATION METRICS FINAL REPORT  ===")
    logger.info("==========================================")
    logger.info(s"Total messages sent:    $totalSent")
    logger.info(s"Total messages dropped: $totalDropped")
    MessageType.values.foreach: kind =>
      val sent = sentCounts
        .filter(_._1._3 == kind)
        .values.map(_.get).sum
      val dropped = droppedCounts
        .filter(_._1._2 == kind)
        .values.map(_.get).sum
      if sent > 0 || dropped > 0 then
        logger.info(s"  $kind → sent: $sent, dropped: $dropped")
    logger.info("==========================================")