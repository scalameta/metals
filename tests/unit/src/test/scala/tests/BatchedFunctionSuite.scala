package tests

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.BatchedFunction
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.CancelableFuture

class BatchedFunctionSuite extends BaseSuite {
  test("batch") {
    implicit val ec = ExecutionContext.global

    val lock = new Object
    val mkString = BatchedFunction.fromFuture[String, String](
      { numbers =>
        Future {
          lock.synchronized {
            numbers.mkString
          }
        }
      },
      "exaample",
    )
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
    BatchedFunction.fromFuture[String, String](
      { numbers =>
        Future.successful {
          numbers.mkString
        }
      },
      "example",
    )

  test("cancel2") {
    val executorService = Executors.newFixedThreadPool(10)
    val ec2 = ExecutionContext.fromExecutor(executorService)
    var i = 1
    val stuckExample: BatchedFunction[String, String] =
      new BatchedFunction(
        (seq: Seq[String]) => {
          seq.toList match {
            case "loop" :: Nil =>
              val future = Future.apply {
                while (i == 1) {
                  Thread.sleep(1)
                }
                "loop-result"
              }(ec2)
              CancelableFuture[String](future, Cancelable { () => i = 2 })
            case _ =>
              CancelableFuture[String](
                Future.successful("result"),
                Cancelable.empty,
              )
          }
        },
        "stuck example",
        default = Some("default"),
      )(ec2)
    val cancelled = stuckExample("loop")
    assertEquals(i, 1)
    assert(cancelled.value.isEmpty)
    val normal = stuckExample("normal")
    stuckExample.cancelCurrent()
    for {
      str <- cancelled
      _ = assertEquals(i, 2)
      _ = assertEquals(str, "default")
      str <- normal
      _ = assertEquals(str, "result")
    } yield ()
  }
}
