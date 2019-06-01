package tests.pc

import tests.BaseSuite
import scala.meta.internal.pc.CompilerJobQueue
import scala.concurrent.Promise
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.mutable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object CompilerJobQueueSuite extends BaseSuite {
  var jobs: CompilerJobQueue = null

  override def utestBeforeEach(path: Seq[String]): Unit = {
    jobs = CompilerJobQueue()
    super.utestBeforeEach(path)
  }
  override def utestAfterEach(path: Seq[String]): Unit = {
    jobs.shutdown()
    super.utestAfterEach(path)
  }

  test("cancel") {
    val cancelled = new CompletableFuture[Unit]()
    cancelled.cancel(false)
    jobs.submit(cancelled, () => {
      Thread.sleep(50)
    })
    jobs.executor.shutdown()
    // Assert that cancelled task never execute.
    jobs.executor.awaitTermination(10, TimeUnit.MILLISECONDS)
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
    assertEquals(obtained.length, size)

    // Assert that the jobs don't run in the default order.
    assertNotEquals(obtained.toList, original)
  }
}
