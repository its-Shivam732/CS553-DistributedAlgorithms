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
 * Tests Lai-Yang snapshot algorithm correctness.
 * Verifies initiator starts snapshot, sends markers,
 * and non-initiators participate correctly.
 */
class LaiYangSnapshotTest
  extends TestKit(ActorSystem("LaiYangSnapshotTest"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private val metrics = MetricsCollector()
  private val sampler = PdfSampler(42L)

  test("LaiYangSnapshot has correct algorithm name"):
    val snap = LaiYangSnapshot(isInitiator = false)
    snap.name shouldBe "LaiYangSnapshot"
  
  test("LaiYangSnapshot initiator sends CONTROL marker on start"):
    val probe    = TestProbe()
    val snapshot = LaiYangSnapshot(isInitiator = true)
    val pdf      = NodePdf(Map(MessageType.CONTROL -> 1.0))

    val actor = system.actorOf(
      NodeActor.props(
        id         = 0,
        metrics    = metrics,
        sampler    = sampler,
        algorithms = List(snapshot)
      )
    )

    actor ! NodeActor.Init(
      neighbors     = Map(1 -> probe.ref),
      allowedOnEdge = Map(1 -> Set(MessageType.CONTROL)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 1.second
    )

    // Initiator must send a CONTROL marker immediately
    val msg = probe.expectMsgClass(3.seconds, classOf[NodeActor.Envelope])
    msg.kind shouldBe MessageType.CONTROL

  test("LaiYangSnapshot non-initiator does not send on start"):
    val probe    = TestProbe()
    val snapshot = LaiYangSnapshot(isInitiator = false)
    val pdf      = NodePdf(Map(MessageType.CONTROL -> 1.0))

    val actor = system.actorOf(
      NodeActor.props(
        id         = 10,
        metrics    = metrics,
        sampler    = sampler,
        algorithms = List(snapshot)
      )
    )

    actor ! NodeActor.Init(
      neighbors     = Map(11 -> probe.ref),
      allowedOnEdge = Map(11 -> Set(MessageType.CONTROL)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 1.second
    )

    // Non-initiator should NOT send anything spontaneously
    probe.expectNoMessage(500.millis)


  test("LaiYangSnapshot and WaveAlgorithm can coexist on same node"):
    val registry = AlgorithmRegistry.build(
      nodeIds       = Set(0, 1),
      initiatorId   = 0,
      enableWave    = true,
      enableLaiYang = true
    )

    val names = registry(0).map(_.name)
    names should contain("WaveAlgorithm")
    names should contain("LaiYangSnapshot")
    names.size shouldBe 2