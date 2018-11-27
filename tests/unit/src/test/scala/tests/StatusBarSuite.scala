package tests

import scala.concurrent.Promise
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.StatusBar

object StatusBarSuite extends BaseSuite {
  val time = new FakeTime
  val client = new TestingClient(PathIO.workingDirectory, Buffers())
  var status: StatusBar = new StatusBar(() => client, time, ProgressTicks.dots)
  override def utestBeforeEach(path: Seq[String]): Unit = {
    client.statusParams.clear()
    status.cancel()
  }

  def tickSecond(): Unit = {
    time.elapseSeconds(1)
    status.tick()
  }

  test("message") {
    status.addMessage("tick 1")
    time.elapseSeconds(5)
    status.addMessage("tick 2")
    status.tick()
    time.elapseSeconds(11)
    status.tick()
    assertNoDiff(
      client.statusBarHistory,
      """|
         |<show> - tick 1
         |tick 2
         |<hide>
         |""".stripMargin
    )
  }

  test("future") {
    val promise = Promise[Unit]()
    status.trackFuture("tick", promise.future)
    1.to(7).foreach { _ =>
      tickSecond()
    }
    promise.success(())
    status.tick()
    assertNoDiff(
      client.statusBarHistory,
      """|
         |<show> - tick
         |tick.
         |tick..
         |tick...
         |tick
         |tick.
         |tick..
         |tick...
         |<hide>
         |""".stripMargin
    )
  }

  test("race") {
    val promise1 = Promise[Unit]()
    val promise2 = Promise[Unit]()
    status.trackFuture("a", promise1.future, showTimer = true)
    tickSecond()
    status.trackFuture("b", promise2.future, showTimer = true)
    1.to(2).foreach { _ =>
      tickSecond()
    }
    promise1.success(())
    1.to(2).foreach { _ =>
      tickSecond()
    }
    promise2.success(())
    status.tick()
    assertNoDiff(
      client.statusBarHistory,
      """|
         |<show> - a
         |a 1s
         |a 2s
         |b 2s
         |b 3s
         |b 4s
         |<hide>
         |""".stripMargin
    )
  }
}
