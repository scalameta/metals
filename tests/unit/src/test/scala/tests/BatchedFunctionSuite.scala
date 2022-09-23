package tests

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Success

import scala.meta.internal.metals.BatchedFunction

class BatchedFunctionSuite extends BaseSuite {
  test("batch") {
    implicit val ec = ExecutionContext.global

    val lock = new Object
    val mkString = BatchedFunction.fromFuture[String, String]({ numbers =>
      Future {
        lock.synchronized {
          numbers.mkString
        }
      }
    })
    val results = lock.synchronized {
      List(
        // First request instantly acquires lock and runs solo
        mkString(List("a")),
        // Following requests will be buffered and run together
        mkString(List("b")),
        mkString(List("c")),
      )
    }

    Future.sequence(results).map { results =>
      assertNoDiff(
        results.mkString("\n"),
        """
          |a
          |bc
          |bc
        """.stripMargin,
      )
    }
  }

  implicit val ec: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(cause: Throwable): Unit = cause.printStackTrace()
  }

  def batchedExample(): BatchedFunction[String, String] =
    BatchedFunction.fromFuture[String, String] { numbers =>
      Future.successful {
        numbers.mkString
      }
    }

  test("pause") {

    val mkString = batchedExample()

    val unpaused = mkString(List("a"))
    assertDiffEqual(unpaused.value, Some(Success("a")))

    mkString.pause()

    val paused = mkString("b")
    assert(!paused.isCompleted)
    val paused2 = mkString("c")
    assert(!paused2.isCompleted)

    mkString.unpause()

    assertDiffEqual(paused.value, Some(Success("bc")))
    assertDiffEqual(paused2.value, Some(Success("bc")))
  }

  test("cancel") {
    val mkString = batchedExample()

    val unpaused = mkString(List("a"))
    assertDiffEqual(unpaused.value, Some(Success("a")))

    mkString.pause()

    val paused = mkString("b")
    assert(!paused.isCompleted)
    val paused2 = mkString("c")
    assert(!paused2.isCompleted)

    mkString.cancelAll()

    mkString.unpause()

    assertDiffEqual(paused.value, None)
    assertDiffEqual(paused2.value, None)

    val unpaused2 = mkString(List("a", "b"))
    assertDiffEqual(unpaused2.value, Some(Success("ab")))

  }
}
