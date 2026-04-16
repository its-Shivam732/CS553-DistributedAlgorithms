package com.uic.cs553.distributed.framework

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.uic.cs553.distributed.simcore.*
import com.uic.cs553.distributed.simruntimeakka.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

/**
 * Tests NodeActor message handling.
 * Uses Akka TestKit to verify actor behavior without
 * running the full simulation.
 */
class NodeActorTest
  extends TestKit(ActorSystem("NodeActorTest"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  private val metrics = MetricsCollector()
  private val sampler = PdfSampler(42L)

  test("NodeActor initializes correctly with Init message"):
    val actor = system.actorOf(
      NodeActor.props(id = 0, metrics = metrics, sampler = sampler)
    )
    val neighbor = TestProbe()
    val pdf = NodePdf(Map(
      MessageType.PING -> 0.60,
      MessageType.WORK -> 0.40
    ))

    // Send Init — should not crash
    actor ! NodeActor.Init(
      neighbors     = Map(1 -> neighbor.ref),
      allowedOnEdge = Map(1 -> Set(MessageType.PING, MessageType.WORK)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 100.millis
    )
    // No exception = test passes
    Thread.sleep(100)

  test("NodeActor handles ExternalInput message"):
    val actor    = system.actorOf(
      NodeActor.props(id = 1, metrics = metrics, sampler = sampler)
    )
    val neighbor = TestProbe()
    val pdf      = NodePdf(Map(MessageType.PING -> 1.0))

    actor ! NodeActor.Init(
      neighbors     = Map(2 -> neighbor.ref),
      allowedOnEdge = Map(2 -> Set(MessageType.PING)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 1.second
    )
    Thread.sleep(100)

    // Inject external PING — should forward to neighbor
    actor ! NodeActor.ExternalInput(MessageType.PING, "test-payload")
    neighbor.expectMsgClass(2.seconds, classOf[NodeActor.Envelope])

  test("NodeActor blocks forbidden message types on edges"):
    val actor    = system.actorOf(
      NodeActor.props(id = 2, metrics = metrics, sampler = sampler)
    )
    val neighbor = TestProbe()
    val pdf      = NodePdf(Map(MessageType.PING -> 1.0))

    // Edge only allows PING — not WORK
    actor ! NodeActor.Init(
      neighbors     = Map(3 -> neighbor.ref),
      allowedOnEdge = Map(3 -> Set(MessageType.PING)),
      pdf           = pdf,
      timerEnabled  = false,
      tickEvery     = 1.second
    )
    Thread.sleep(100)

    // Inject WORK — should be BLOCKED (not forwarded)
    actor ! NodeActor.ExternalInput(MessageType.WORK, "blocked")
    neighbor.expectNoMessage(500.millis)

  test("MetricsCollector tracks sent messages correctly"):
    val m = MetricsCollector()
    m.recordSent(0, 1, MessageType.PING)
    m.recordSent(0, 1, MessageType.PING)
    m.recordSent(1, 2, MessageType.WORK)
    m.totalSent shouldBe 3

  test("MetricsCollector tracks dropped messages correctly"):
    val m = MetricsCollector()
    m.recordDropped(0, 1, MessageType.WORK)
    m.recordDropped(0, 1, MessageType.GOSSIP)
    m.totalDropped shouldBe 2