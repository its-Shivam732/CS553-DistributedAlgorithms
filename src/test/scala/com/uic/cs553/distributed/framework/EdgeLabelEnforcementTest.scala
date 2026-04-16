package com.uic.cs553.distributed.framework

import com.uic.cs553.distributed.simcore.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests that edge labels correctly enforce which message
 * types are allowed on each channel.
 * This is a core requirement — forbidden types must never traverse an edge.
 */
class EdgeLabelEnforcementTest extends AnyFunSuite with Matchers:

  test("EdgeLabel allows configured message types"):
    val label = EdgeLabel(0, 1, Set(MessageType.PING, MessageType.CONTROL))
    label.allows(MessageType.PING)    shouldBe true
    label.allows(MessageType.CONTROL) shouldBe true

  test("EdgeLabel blocks unconfigured message types"):
    val label = EdgeLabel(0, 1, Set(MessageType.PING, MessageType.CONTROL))
    label.allows(MessageType.WORK)   shouldBe false
    label.allows(MessageType.GOSSIP) shouldBe false
    label.allows(MessageType.ACK)    shouldBe false

  test("EdgeLabel.default allows CONTROL and PING only"):
    val label = EdgeLabel.default(0, 1)
    label.allows(MessageType.CONTROL) shouldBe true
    label.allows(MessageType.PING)    shouldBe true
    label.allows(MessageType.WORK)    shouldBe false
    label.allows(MessageType.GOSSIP)  shouldBe false

  test("EdgeLabel.allowAll permits every message type"):
    val label = EdgeLabel.allowAll(0, 1)
    MessageType.values.foreach: kind =>
      label.allows(kind) shouldBe true

  test("EnrichedGraph correctly routes allowedOn queries"):
    val graph = SimGraph(
      nodes = Set(0, 1, 2),
      edges = Set(SimEdge(0, 1), SimEdge(1, 2))
    )
    val edgeLabels = Map(
      (0, 1) -> EdgeLabel(0, 1, Set(MessageType.PING, MessageType.CONTROL)),
      (1, 2) -> EdgeLabel(1, 2, Set(MessageType.WORK, MessageType.CONTROL))
    )
    val nodePdfs = Map(
      0 -> NodePdf.uniform,
      1 -> NodePdf.uniform,
      2 -> NodePdf.uniform
    )
    val enriched = EnrichedGraph(graph, edgeLabels, nodePdfs)

    enriched.allowedOn(0, 1) should contain(MessageType.PING)
    enriched.allowedOn(0, 1) should not contain MessageType.WORK
    enriched.allowedOn(1, 2) should contain(MessageType.WORK)
    enriched.allowedOn(1, 2) should not contain MessageType.PING