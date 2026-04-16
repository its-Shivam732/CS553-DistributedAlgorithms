package com.uic.cs553.distributed.framework

import com.uic.cs553.distributed.simcore.*
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests SimGraph topology operations.
 * Verifies correct neighbor resolution — critical for
 * actor wiring in GraphToActorMapper.
 */
class SimGraphTest extends AnyFunSuite with Matchers:

  private val testGraph = SimGraph(
    nodes = Set(0, 1, 2, 3, 4),
    edges = Set(
      SimEdge(0, 1),
      SimEdge(0, 2),
      SimEdge(1, 3),
      SimEdge(2, 3),
      SimEdge(3, 4)
    )
  )

  test("SimGraph reports correct node count"):
    testGraph.nodeCount shouldBe 5

  test("SimGraph reports correct edge count"):
    testGraph.edgeCount shouldBe 5

  test("outNeighbors returns correct neighbors"):
    testGraph.outNeighbors(0) shouldBe Set(1, 2)
    testGraph.outNeighbors(1) shouldBe Set(3)
    testGraph.outNeighbors(4) shouldBe Set.empty  // leaf node

  test("inNeighbors returns correct neighbors"):
    testGraph.inNeighbors(3) shouldBe Set(1, 2)
    testGraph.inNeighbors(0) shouldBe Set.empty  // root node

  test("hasEdge correctly identifies existing edges"):
    testGraph.hasEdge(0, 1) shouldBe true
    testGraph.hasEdge(0, 2) shouldBe true
    testGraph.hasEdge(3, 4) shouldBe true

  test("hasEdge correctly rejects non-existing edges"):
    testGraph.hasEdge(1, 0) shouldBe false  // directed — reverse doesn't exist
    testGraph.hasEdge(4, 3) shouldBe false
    testGraph.hasEdge(0, 4) shouldBe false  // no direct edge

  test("leaf nodes have no outgoing neighbors"):
    testGraph.outNeighbors(4) shouldBe Set.empty

  test("root nodes have no incoming neighbors"):
    testGraph.inNeighbors(0) shouldBe Set.empty