package scala.meta.internal.metals

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.async.ConcurrentQueue
import scala.meta.internal.metals.MetalsEnrichments._

/**
 * Helper to batch multiple asynchronous requests and ensure only one request is active at a time.
 *
 * @param fn the function to batch. It must be safe to group together arguments
 *           from different requests into a single request and respond to the
 *           aggregated requests with the aggregated response.
 */
final class BatchedFunction[A, B](
    fn: Seq[A] => CancelableFuture[B],
    functionId: String,
    shouldLogQueue: Boolean = false,
    default: Option[B] = None,
)(implicit ec: ExecutionContext)
    extends (Seq[A] => Future[B])
    with Function2[Seq[A], () => Unit, Future[B]] {

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
      callback: () => Unit,
  ): Future[B] = {
    val promise = Promise[B]()
    logQueue()
    queue.add(Request(arguments, promise, callback))
    runAcquire()
    promise.future
  }

  def logQueue(): Unit = {
    if (shouldLogQueue && !queue.isEmpty()) {
      scribe.trace(
        s"Current $functionId queue: \n" + queue.toArray
          .map {
            case Request(args, promise, _) =>
              args.mkString(",") + s" -> isCompleted ${promise.isCompleted}"
            case _ => ""
          }
          .mkString("\n")
      )
    }
  }

  def apply(arguments: Seq[A]): Future[B] = {
    apply(arguments, () => ())
  }

  def apply(
      argument: A
  ): Future[B] = apply(List(argument), () => ())

  def cancelAll(): Unit = {
    val requests = ConcurrentQueue.pollAll(queue)
    requests.foreach(_.result.complete(defaultResult))
    cancelCurrent()
  }

  def cancelCurrent(): Unit = {
    lock.get() match {
      case None =>
      case Some(promise) =>
        promise.tryFailure(new BatchedFunction.BatchedFunctionCancelation)
    }
  }

  def currentFuture(): Future[B] = {
    current.get().future
  }

  private val current = new AtomicReference(
    CancelableFuture[B](
      Future.failed(new NoSuchElementException("BatchedFunction")),
      Cancelable.empty,
    )
  )

  private val queue = new ConcurrentLinkedQueue[Request]()
  private case class Request(
      arguments: Seq[A],
      result: Promise[B],
      callback: () => Unit,
  )

  private val lock = new AtomicReference[Option[Promise[B]]](None)

  private def unlock(): Unit = {
    lock.set(None)
    if (!queue.isEmpty) {
      scribe.debug(s"Queue $functionId is empty, running acquire")
      runAcquire()
    }
  }
  private def runAcquire(): Unit = {
    lazy val promise = {
      val p = Promise[B]
      p.future.onComplete { _ => unlock() }
      p
    }
    if (lock.compareAndSet(None, Some(promise))) {
      runRelease(promise)
    } else {
      // Do nothing, the submitted arguments will be handled
      // by a separate request.
    }
  }
  private def runRelease(p: Promise[B]): Unit = {
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
        scribe.trace(s"Running function inside queue $functionId")
        val result = fn(args)
        this.current.set(result)
        val resultF = for {
          result <- result.future
          _ <- Future { callbacks.foreach(cb => cb()) }
        } yield result
        resultF.onComplete(p.tryComplete)
        p.future.onComplete {
          case Failure(_: BatchedFunction.BatchedFunctionCancelation) =>
            result.cancel()
            requests.foreach(_.result.complete(defaultResult))
          case result =>
            requests.foreach(_.result.complete(result))
        }
      } else {
        p.tryFailure(new BatchedFunction.BatchedFunctionCancelation)
      }
    } catch {
      case NonFatal(e) =>
        unlock()
        requests.foreach(_.result.tryFailure(e))
        scribe.error(s"Unexpected error releasing buffered job", e)
    }
  }

  def defaultResult: Try[B] =
    default.map(Success(_)).getOrElse(Failure(new CancellationException))
}

object BatchedFunction {

  /**
   * Delays executing the given function, creating a debounce-like effect with
   * the important difference that the function flushes events every time the
   * flushDelay has passed from the first invocation. A true debounce will
   * indefinitely debounce if you're constantly firing the event.
   */
  def backpressure[A, B](
      functionId: String,
      flushDelay: FiniteDuration,
      sh: ScheduledExecutorService,
      shouldLogQueue: Boolean = false,
      default: Option[B] = None,
  )(
      fn: Seq[A] => Future[B]
  )(implicit ec: ExecutionContext): BatchedFunction[A, B] = {
    BatchedFunction.fromFuture(
      values => {
        for {
          _ <- sh.sleep(flushDelay)
          result <- fn(values)
        } yield result
      },
      functionId,
      shouldLogQueue,
      default,
    )
  }

  def fromFuture[A, B](
      fn: Seq[A] => Future[B],
      functionId: String,
      shouldLogQueue: Boolean = false,
      default: Option[B] = None,
  )(implicit
      ec: ExecutionContext
  ): BatchedFunction[A, B] =
    new BatchedFunction(
      fn.andThen(CancelableFuture(_)),
      functionId,
      shouldLogQueue,
      default,
    )
  class BatchedFunctionCancelation extends RuntimeException
}
