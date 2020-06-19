package scala.meta.internal.pc

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.{util => ju}

/**
 * A thread pool executor to execute jobs on a single thread in a last-in-first-out order.
 *
 * The last-in-first-out order is important because it's common for Metals
 * users to send multiple completion/hover/signatureHelp requests in rapid
 * succession. In these situations, we care most about responding to the latest
 * request even if it comes at the expense of ignoring older requests.
 *
 * To restrict unsafe multi-threaded access to the presentation compiler we
 * schedule jobs to run on a single thread. We use this executor instead of the
 * presentation compiler thread (see MetalsGlobalThread) for the following reasons:
 * - we limit the usage of sleep/notify/wait/synchronized primitives.
 * - some blocking compiler APIs like `ask[T](op: () => T)` don't seem to work as advertised.
 * - it's preferable to work on top of CompletableFuture[T] instead of the custom Response[T] from the compiler,
 *   which is required to execute tasks on the presentation compiler thread:
 *   - CompletableFuture[T] can be passed via Java-only reflection APIs in mtags-interfaces.
 *   - CompletableFuture[T] can be returned to lsp4j, for non-blocking JSON-RPC request handling.
 *   - CompletableFuture[T] can be converted to Scala Futures for easier composition.
 */
class CompilerJobQueue(newExecutor: () => ThreadPoolExecutor) {
  private val myExecutor = new AtomicReference[ThreadPoolExecutor]()
  def executor(): ThreadPoolExecutor = {
    val result = myExecutor.get()
    if (result != null) {
      if (result.isShutdown()) {
        myExecutor.compareAndSet(result, null)
        executor()
      } else {
        result
      }
    } else {
      val next = newExecutor()
      val isSet = myExecutor.compareAndSet(null, next)
      if (!isSet) {
        next.shutdown()
      }
      myExecutor.get()
    }
  }
  override def toString(): String = s"CompilerJobQueue(${myExecutor.get()})"
  def shutdown(): Unit = {
    val ex = myExecutor.get()
    if (ex != null) {
      ex.shutdown()
      myExecutor.compareAndSet(ex, null)
    }
  }
  def submit(fn: () => Unit): Unit = {
    submit(new CompletableFuture[Unit](), fn)
  }
  def submit(result: CompletableFuture[_], fn: () => Unit): Unit = {
    executor().execute(new CompilerJobQueue.Job(result, fn))
  }
  // The implementation of `Executors.newSingleThreadExecutor()` uses finalize.
  override def finalize(): Unit = {
    shutdown()
  }
}

object CompilerJobQueue {

  def apply(): CompilerJobQueue = {
    new CompilerJobQueue(() => {
      val singleThreadExecutor = new ThreadPoolExecutor(
        /* corePoolSize */ 1,
        /* maximumPoolSize */ 1,
        /* keepAliveTime */ 0,
        /* unit */ TimeUnit.MILLISECONDS,
        /* workQueue */ new LastInFirstOutBlockingQueue
      )
      singleThreadExecutor.setRejectedExecutionHandler((r, _) => {
        r match {
          case j: Job =>
            j.reject()
          case _ =>
        }
      })
      singleThreadExecutor
    })
  }

  /**
   * Runnable with a timestamp and attached completable future. */
  private class Job(result: CompletableFuture[_], _run: () => Unit)
      extends Runnable {
    def reject(): Unit = {
      result.completeExceptionally(new CancellationException("rejected"))
    }
    val start: Long = System.nanoTime()
    def run(): Unit = {
      if (!result.isDone()) {
        _run()
      }
    }
  }

  /**
   * Priority queue that runs the most recently submitted task first. */
  private class LastInFirstOutBlockingQueue
      extends PriorityBlockingQueue[Runnable](
        10,
        new ju.Comparator[Runnable] {
          def compare(o1: Runnable, o2: Runnable): Int = {
            // Downcast is safe because we only submit `Job` runnables into this
            // threadpool via `CompilerJobQueue.submit`. We can't make the queue
            // `PriorityBlockingQueue[Job]` because `new ThreadPoolExecutor` requires
            // a `BlockingQueue[Runnable]` and Java queues are invariant.
            -java.lang.Long.compare(
              o1.asInstanceOf[Job].start,
              o2.asInstanceOf[Job].start
            )
          }
        }
      )
}
