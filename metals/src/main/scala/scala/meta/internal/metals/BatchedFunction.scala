package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.internal.async.ConcurrentQueue

final case class BatchedCallbackData[A, B](
    arguments: Seq[A],
    result: B
)

object BatchedCallbackData {
  def noop[A, B]: BatchedCallbackData[A, B] => Unit = _ => ()
}

/**
 * Helper to batch multiple asynchronous requests and ensure only one request is active at a time.
 *
 * @param fn the function to batch. It must be safe to group together arguments
 *           from different requests into a single request and respond to the
 *           aggregated requests with the aggregated response.
 */
final class BatchedFunction[A, B](
    fn: Seq[A] => CancelableFuture[B]
)(implicit ec: ExecutionContext)
    extends (Seq[A] => Future[B])
    with Function2[Seq[A], BatchedCallbackData[A, B] => Unit, Future[B]]
    with Pauseable {

  /**
   * Call the function with the given arguments.
   *
   * It is safe to rapidly call this function multiple times. The first call
   * triggers the function and subsequent arguments will be batched up together
   * for another run once the first asynchronous call completes.
   *
   * @return the response from calling the batched function with potentially
   *         previously and/or subsequently batched arguments.
   */
  def apply(
      arguments: Seq[A],
      callback: BatchedCallbackData[A, B] => Unit
  ): Future[B] = {
    val promise = Promise[B]()
    queue.add(Request(arguments, promise, callback))
    runAcquire()
    promise.future
  }

  def apply(arguments: Seq[A]): Future[B] = {
    apply(arguments, BatchedCallbackData.noop)
  }

  def apply(
      argument: A
  ): Future[B] = apply(List(argument), BatchedCallbackData.noop)

  override def doUnpause(): Unit = {
    unlock()
  }

  def cancelCurrentRequest(): Unit = {
    current.get().cancelable.cancel()
  }
  def currentFuture(): Future[B] = {
    current.get().future
  }

  private val current = new AtomicReference(
    CancelableFuture[B](
      Future.failed(new NoSuchElementException("BatchedFunction")),
      Cancelable.empty
    )
  )

  private val queue = new ConcurrentLinkedQueue[Request]()
  private case class Request(
      arguments: Seq[A],
      result: Promise[B],
      callback: BatchedCallbackData[A, B] => Unit
  )

  private val lock = new AtomicBoolean()
  private def unlock(): Unit = {
    lock.set(false)
    if (!queue.isEmpty) {
      runAcquire()
    }
  }
  private def runAcquire(): Unit = {
    if (!isPaused.get() && lock.compareAndSet(false, true)) {
      runRelease()
    } else {
      // Do nothing, the submitted arguments will be handled
      // by a separate request.
    }
  }
  private def runRelease(): Unit = {
    // Pre-condition: lock is acquired.
    // Pos-condition:
    //   - lock is released
    //      - instantly if job queue is empty or unexpected exception
    //      - asynchronously once `fn` completes if job que is non-empty
    //   - all pending requests in job queue will be completed
    val requests = ConcurrentQueue.pollAll(queue)
    try {
      if (requests.nonEmpty) {
        val args = requests.flatMap(_.arguments)
        val callbacks = requests.map(_.callback)
        val result = fn(args)
        this.current.set(result)
        val resultF = for {
          result <- result.future
          _ <- Future {
            val callbackData = BatchedCallbackData(args, result)
            callbacks.foreach(cb => cb(callbackData))
          }
        } yield result
        resultF.onComplete { response =>
          unlock()
          requests.foreach(_.result.complete(response))
        }
      } else {
        unlock()
      }
    } catch {
      case NonFatal(e) =>
        unlock()
        requests.foreach(_.result.failure(e))
        scribe.error(s"Unexpected error releasing buffered job", e)
    }
  }
}

object BatchedFunction {
  def fromFuture[A, B](fn: Seq[A] => Future[B])(implicit
      ec: ExecutionContext,
      dummy: DummyImplicit
  ): BatchedFunction[A, B] =
    new BatchedFunction(fn.andThen(CancelableFuture(_)))
}
