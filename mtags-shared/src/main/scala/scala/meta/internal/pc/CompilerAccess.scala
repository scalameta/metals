package scala.meta.internal.pc

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContextExecutor
import scala.util.control.NonFatal

import scala.meta.internal.metals.PcQueryContext
import scala.meta.pc.CancelToken
import scala.meta.pc.PresentationCompilerConfig

import org.slf4j.Logger

/**
 * Manages the lifecycle and multi-threaded access to the presentation compiler.
 *
 * - automatically restarts the compiler on miscellaneous crashes.
 * - handles cancellation via `Thread.interrupt()` to stop the compiler during typechecking,
 *   for functions that support cancellation.
 */
abstract class CompilerAccess[Reporter, Compiler](
    logger: Logger,
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => CompilerWrapper[Reporter, Compiler],
    shouldResetJobQueue: Boolean,
    id: String = ""
)(implicit ec: ExecutionContextExecutor) {

  private val jobs = CompilerJobQueue(id)
  private var _compiler: CompilerWrapper[Reporter, Compiler] = _
  private def isEmpty: Boolean = _compiler == null
  private def isDefined: Boolean = !isEmpty
  private def loadCompiler(): CompilerWrapper[Reporter, Compiler] =
    synchronized {
      if (_compiler == null) {
        _compiler = newCompiler()
      }
      _compiler.resetReporter()
      _compiler
    }

  protected def newReporter: Reporter

  def reporter: Reporter =
    if (isEmpty) newReporter
    else _compiler.reporterAccess.reporter

  def isLoaded(): Boolean = _compiler != null

  def shutdown(): Unit = {
    shutdownCurrentCompiler()
    jobs.shutdown()
  }

  def shutdownCurrentCompiler(): Unit = {
    val compiler = _compiler
    if (compiler != null) {
      compiler.askShutdown()
      _compiler = null
      sh.foreach { scheduler =>
        scheduler.schedule[Unit](
          () => {
            if (compiler.isAlive()) {
              compiler.stop()
            }
          },
          2,
          TimeUnit.SECONDS
        )
      }
    }
  }

  /**
   * Asynchronously execute a function on the compiler thread with `Thread.interrupt()` cancellation.
   */
  def withInterruptableCompiler[T](
      default: T,
      token: CancelToken
  )(
      thunk: CompilerWrapper[Reporter, Compiler] => T
  )(implicit queryInfo: PcQueryContext): CompletableFuture[T] = {
    val isFinished = new AtomicBoolean(false)
    var queueThread = Option.empty[Thread]
    val result = onCompilerJobQueue(
      () => {
        queueThread = Some(Thread.currentThread())
        try withSharedCompiler(default)(thunk)
        finally isFinished.set(true)
      },
      token
    )

    // Interrupt the queue thread
    token.onCancel.whenCompleteAsync(
      (isCancelled, _) => {
        queueThread.foreach { thread =>
          if (
            isCancelled &&
            isFinished.compareAndSet(false, true) &&
            isDefined
          ) {
            _compiler.presentationCompilerThread match {
              case None => // don't interrupt if we don't have separate thread
              case Some(pcThread) =>
                pcThread.interrupt()
                if (thread != pcThread) thread.interrupt()
            }
          }
        }
      },
      ec
    )
    result
  }

  /**
   * Asynchronously execute a function on the compiler thread without `Thread.interrupt()` cancellation.
   *
   * Note that the function is still cancellable.
   */
  def withNonInterruptableCompiler[T](
      default: T,
      token: CancelToken
  )(
      thunk: CompilerWrapper[Reporter, Compiler] => T
  )(implicit queryInfo: PcQueryContext): CompletableFuture[T] = {
    onCompilerJobQueue(
      () => withSharedCompiler(default)(thunk),
      token
    )
  }

  /**
   * Execute a function on the current thread without cancellation support.
   *
   * May potentially run in parallel with other requests, use carefully.
   */
  def withSharedCompiler[T](
      default: T
  )(
      thunk: CompilerWrapper[Reporter, Compiler] => T
  )(implicit queryInfo: PcQueryContext): T = {
    try {
      thunk(loadCompiler())
    } catch {
      case InterruptException() =>
        default
      case other: Throwable =>
        handleSharedCompilerException(other)
          .map { message =>
            retryWithCleanCompiler(
              thunk,
              default,
              message
            )
          }
          .getOrElse {
            handleError(other)
            default
          }
    }
  }

  // TODO: make this return `String`, we should not hide meaningful exceptions
  protected def handleSharedCompilerException(t: Throwable): Option[String]

  protected def ignoreException(t: Throwable): Boolean

  private def retryWithCleanCompiler[T](
      thunk: CompilerWrapper[Reporter, Compiler] => T,
      default: T,
      cause: String
  )(implicit queryInfo: PcQueryContext): T = {
    shutdownCurrentCompiler()
    logger.info(
      s"compiler crashed due to $cause, retrying with new compiler instance."
    )
    try thunk(loadCompiler())
    catch {
      case InterruptException() =>
        default
      case NonFatal(e) =>
        handleError(e)
        default
    }
  }

  private def handleError(
      e: Throwable
  )(implicit queryInfo: PcQueryContext): Unit = {
    queryInfo.report("compiler-error", e, "")
    shutdownCurrentCompiler()
  }

  private def onCompilerJobQueue[T](
      thunk: () => T,
      token: CancelToken
  ): CompletableFuture[T] = {
    val result = new CompletableFuture[T]()
    jobs.submit(
      result,
      { () =>
        try {
          if (token.isCanceled()) {
            result.completeExceptionally(new CancellationException())
          } else {
            Thread.interrupted() // clear interrupt bit
            result.complete(thunk())
          }
        } catch {
          case NonFatal(e) =>
            logger.error(s"presentation compiler error ${e.getMessage()}", e)
            result.completeExceptionally(e)
        }
      }
    )

    // User cancelled task.
    token.onCancel.whenCompleteAsync(
      (isCancelled, _) => {
        if (isCancelled && !result.isDone()) {
          result.cancel(false)
        }
      },
      ec
    )
    // Task has timed out, cancel this request and shutdown the current compiler.
    sh.foreach { scheduler =>
      scheduler.schedule[Unit](
        { () =>
          if (!result.isDone()) {
            try {
              if (shouldResetJobQueue) jobs.reset()
              result.cancel(false)
              shutdownCurrentCompiler()
            } catch {
              case NonFatal(_) =>
              case other: Throwable =>
                if (!ignoreException(other)) throw other
            }
          }
        },
        config.timeoutDelay(),
        config.timeoutUnit()
      )
    }

    result
  }
}
