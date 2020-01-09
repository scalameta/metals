package tests

import scala.concurrent.Future
import scala.meta.internal.metals.BatchedFunction
import scala.concurrent.ExecutionContext
import scala.util.Success

class BatchedFunctionSuite extends BaseSuite {
  testAsync("batch") {
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
        mkString(List("c"))
      )
    }

    Future.sequence(results).map { results =>
      assertNoDiff(
        results.mkString("\n"),
        """
          |a
          |bc
          |bc
        """.stripMargin
      )
    }
  }

  test("pause") {
    implicit val ec = new ExecutionContext {
      def execute(runnable: Runnable): Unit = runnable.run()
      def reportFailure(cause: Throwable): Unit = cause.printStackTrace()
    }

    val mkString = BatchedFunction.fromFuture[String, String] { numbers =>
      Future.successful {
        numbers.mkString
      }
    }

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
}
