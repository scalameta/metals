package tests.pc

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import scala.meta.internal.pc.CompilerJobQueue

import tests.BaseSuite

class CompilerJobQueueSuite extends BaseSuite {
  var jobs: CompilerJobQueue = null

  override def beforeEach(context: BeforeEach): Unit = {
    jobs = CompilerJobQueue()
    super.beforeEach(context)
  }
  override def afterEach(context: AfterEach): Unit = {
    jobs.shutdown()
    super.afterEach(context)
  }

  test("cancel") {
    val cancelled = new CompletableFuture[Unit]()
    cancelled.cancel(false)
    jobs.submit(
      cancelled,
      () => {
        Thread.sleep(50)
      }
    )
    jobs.executor().shutdown()
    // Assert that cancelled task never execute.
    jobs.executor().awaitTermination(10, TimeUnit.MILLISECONDS)
  }

  test("order") {
    val obtained = mutable.ListBuffer.empty[Int]
    val size = 10
    val original = 1.to(size).toList
    val all = Future.traverse(original) { i =>
      val promise = Promise[Unit]()
      jobs.submit(() => {
        Thread.sleep(i * 5)
        obtained += i
        promise.success(())
      })
      promise.future
    }

    Await.result(all, Duration("1s"))

    // Assert all submitted non-cancelled jobs completed.
    assertDiffEqual(obtained.length, size)

    // Assert that the jobs don't run in the default order.
    assertDiffNotEqual(obtained.toList, original)
  }
}
