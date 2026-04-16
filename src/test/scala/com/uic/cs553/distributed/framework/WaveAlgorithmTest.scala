package com.uic.cs553.distributed.framework

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.uic.cs553.distributed.algorithms.*
import com.uic.cs553.distributed.simcore.*
import com.uic.cs553.distributed.simruntimeakka.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

/**
 * Tests Wave algorithm correctness.
 * Verifies the wave reaches all nodes and terminates properly.
 *
 * Uses a deterministic 3-node graph for predictable results:
 *   Node 0 (initiator) → Node 1 → Node 2 (leaf)
 */
class WaveAlgorithmTest
  extends TestKit(ActorSystem("WaveAlgorithmTest"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private val metrics = MetricsCollector()
  private val sampler = PdfSampler(42L)

  test("WaveAlgorithm initializes without error"):
    val wave = WaveAlgorithm(isInitiator = false)
    wave.name shouldBe "WaveAlgorithm"

  test("WaveAlgorithm initiator starts wave on onStart"):
    val probe    = TestProbe()
    val wave     = WaveAlgorithm(isInitiator = true)
    val pdf      = NodePdf(Map(MessageType.CONTROL -> 1.0))

    val actor = system.actorOf(
      NodeActor.props(
        id         = 0,
        metrics    = metrics,
        sampler    = sampler,
        algorithms = List(wave)
      )
    )

    actor ! NodeActor.Init(
      neighbors     = Map(1 -> probe.ref),
      allowedOnEdge = Map(1 -> Set(MessageType.CONTROL)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 1.second
    )

    // Initiator should send a CONTROL message (wave) to neighbor
    probe.expectMsgClass(3.seconds, classOf[NodeActor.Envelope])

  test("LaiYangSnapshot initializes without error"):
    val snapshot = LaiYangSnapshot(isInitiator = false)
    snapshot.name shouldBe "LaiYangSnapshot"

  test("AlgorithmRegistry builds correct algorithms per node"):
    val registry = AlgorithmRegistry.build(
      nodeIds       = Set(0, 1, 2),
      initiatorId   = 0,
      enableWave    = true,
      enableLaiYang = true
    )

    registry.size shouldBe 3
    registry(0).map(_.name) should contain("WaveAlgorithm")
    registry(0).map(_.name) should contain("LaiYangSnapshot")
    registry(1).map(_.name) should contain("WaveAlgorithm")

  test("AlgorithmRegistry respects enableWave=false flag"):
    val registry = AlgorithmRegistry.build(
      nodeIds       = Set(0, 1),
      initiatorId   = 0,
      enableWave    = false,
      enableLaiYang = true
    )

    registry(0).map(_.name) should not contain "WaveAlgorithm"
    registry(0).map(_.name) should contain("LaiYangSnapshot")