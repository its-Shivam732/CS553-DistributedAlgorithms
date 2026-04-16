package com.uic.cs553.distributed.framework

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.uic.cs553.distributed.simcli.*
import com.uic.cs553.distributed.simcore.*
import com.uic.cs553.distributed.simruntimeakka.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import java.io.{File, PrintWriter}
import scala.concurrent.duration.*

/**
 * Tests FileDrivenInjector — verifies that injection
 * scripts are parsed and messages are delivered to the
 * correct actor nodes.
 */
class FileDrivenInjectorTest
  extends TestKit(ActorSystem("FileDrivenInjectorTest"))
    with ImplicitSender
    with AnyFunSuiteLike
    with Matchers
    with BeforeAndAfterAll:

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)

  /** Write a temp inject file and return its path */
  private def writeTempScript(lines: String*): String =
    val f = File.createTempFile("inject-test-", ".txt")
    f.deleteOnExit()
    val pw = PrintWriter(f)
    lines.foreach(pw.println)
    pw.close()
    f.getAbsolutePath

  test("FileDrivenInjector loads entries from valid script"):
    val path = writeTempScript(
      "delayMs=100,node=0,kind=PING,payload=hello",
      "delayMs=200,node=1,kind=WORK,payload=task"
    )
    val entries = FileDrivenInjector.loadScript(path)
    entries.size shouldBe 2

  test("FileDrivenInjector skips comment lines"):
    val path = writeTempScript(
      "# this is a comment",
      "delayMs=100,node=0,kind=PING,payload=hello",
      "# another comment",
      "delayMs=200,node=1,kind=WORK,payload=task"
    )
    val entries = FileDrivenInjector.loadScript(path)
    entries.size shouldBe 2

  test("FileDrivenInjector skips blank lines"):
    val path = writeTempScript(
      "",
      "delayMs=100,node=0,kind=PING,payload=hello",
      "",
      "delayMs=200,node=1,kind=WORK,payload=task",
      ""
    )
    val entries = FileDrivenInjector.loadScript(path)
    entries.size shouldBe 2

  test("FileDrivenInjector parses delayMs correctly"):
    val path    = writeTempScript("delayMs=500,node=0,kind=PING,payload=p")
    val entries = FileDrivenInjector.loadScript(path)
    entries.head.delayMs shouldBe 500

  test("FileDrivenInjector parses node id correctly"):
    val path    = writeTempScript("delayMs=100,node=3,kind=WORK,payload=p")
    val entries = FileDrivenInjector.loadScript(path)
    entries.head.nodeId shouldBe 3

  test("FileDrivenInjector parses message kind correctly"):
    val path    = writeTempScript("delayMs=100,node=0,kind=GOSSIP,payload=p")
    val entries = FileDrivenInjector.loadScript(path)
    entries.head.kind shouldBe MessageType.GOSSIP

  test("FileDrivenInjector parses payload correctly"):
    val path    = writeTempScript("delayMs=100,node=0,kind=PING,payload=my-task-001")
    val entries = FileDrivenInjector.loadScript(path)
    entries.head.payload shouldBe "my-task-001"
  

  test("FileDrivenInjector handles empty script gracefully"):
    val path    = writeTempScript()
    val entries = FileDrivenInjector.loadScript(path)
    entries shouldBe empty