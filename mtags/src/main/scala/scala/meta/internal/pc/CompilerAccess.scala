package scala.meta.internal.pc

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.meta.pc.CancelToken
import java.util.concurrent.CompletableFuture
import scala.meta.pc.PresentationCompilerConfig
import scala.concurrent.ExecutionContextExecutor

/**
 * Manages the lifecycle and multi-threaded access to the presentation compiler.
 *
 * - automatically restarts the compiler on miscellaneous crashes.
 * - handles cancellation via `Thread.interrupt()` to stop the compiler during typechecking,
 *   for functions that support cancellation.
 */
class CompilerAccess(
    config: PresentationCompilerConfig,
    sh: Option[ScheduledExecutorService],
    newCompiler: () => MetalsGlobal
)(implicit ec: ExecutionContextExecutor) {
  private val logger: Logger = Logger.getLogger(classOf[CompilerAccess].getName)

  private val jobs = CompilerJobQueue()
  private def isEmpty: Boolean = _compiler == null
  private def isDefined: Boolean = !isEmpty
  def reporter: StoreReporter =
    if (isEmpty) new StoreReporter()
    else _compiler.reporter.asInstanceOf[StoreReporter]

  def shutdown(): Unit = {
    restart()
    jobs.shutdown()
  }

  def restart(): Unit = {
    val compiler = _compiler
    if (compiler != null) {
      compiler.askShutdown()
      _compiler = null
      sh.foreach { scheduler =>
        scheduler.schedule[Unit](() => {
          if (compiler.presentationCompilerThread.isAlive) {
            compiler.presentationCompilerThread.stop()
          }
        }, 2, TimeUnit.SECONDS)
      }
    }
  }

  /**
   * Asynchronously execute a function on the compiler thread with `Thread.interrupt()` cancellation.
   */
  def withInterruptableCompiler[T](
      default: T,
      token: CancelToken
  )(thunk: MetalsGlobal => T): CompletableFuture[T] = {
    val isFinished = new AtomicBoolean(false)
    var queueThread = Option.empty[Thread]
    val result = onCompilerJobQueue(
      () => {
        queueThread = Some(Thread.currentThread())
        Thread.interrupted() // clear interrupt thread
        try withSharedCompiler(default)(thunk)
        finally isFinished.set(true)
      },
      token
    )
    // Interrupt the queue thread
    token.onCancel.whenCompleteAsync(
      (isCancelled, _) => {
        queueThread.foreach { thread =>
          if (isCancelled &&
            isFinished.compareAndSet(false, true) &&
            isDefined) {
            _compiler.presentationCompilerThread.interrupt()
            if (thread != _compiler.presentationCompilerThread) {
              thread.interrupt()
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
  )(thunk: MetalsGlobal => T): CompletableFuture[T] = {
    onCompilerJobQueue(() => withSharedCompiler(default)(thunk), token)
  }

  /**
   * Execute a function on the current thread without cancellation support.
   *
   * May potentially run in parallel with other requests, use carefully.
   */
  def withSharedCompiler[T](default: T)(thunk: MetalsGlobal => T): T = {
    try {
      thunk(loadCompiler())
    } catch {
      case InterruptException() =>
        default
      case NonFatal(e) =>
        val isParadiseRelated = e.getStackTrace
          .exists(_.getClassName.startsWith("org.scalamacros"))
        val isCompilerRelated = e.getStackTrace.headOption.exists { e =>
          e.getClassName.startsWith("scala.tools") ||
          e.getClassName.startsWith("scala.reflect")
        }
        if (isParadiseRelated) {
          // NOTE(olafur) Metals disables macroparadise by default but other library
          // clients of mtags may enable it.
          // Testing shows that the scalamacro paradise plugin tends to crash
          // easily in long-running sessions. We retry with a fresh compiler
          // to see if that fixes the issue. This is a hacky solution that is
          // slow because creating new compiler instances is expensive. A better
          // long-term solution is to fix the paradise plugin implementation
          // to be  more resilient in long-running sessions.
          retryWithCleanCompiler(
            thunk,
            default,
            "the org.scalamacros:paradise compiler plugin"
          )
        } else if (isCompilerRelated) {
          retryWithCleanCompiler(
            thunk,
            default,
            "an error in the Scala compiler"
          )
        } else {
          handleError(e)
          default
        }
    }
  }

  private def retryWithCleanCompiler[T](
      thunk: MetalsGlobal => T,
      default: T,
      cause: String
  ): T = {
    restart()
    logger.log(
      Level.INFO,
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

  private def handleError(e: Throwable): Unit = {
    CompilerThrowable.trimStackTrace(e)
    logger.log(Level.SEVERE, e.getMessage, e)
    restart()
  }
  private var _compiler: MetalsGlobal = _
  private def loadCompiler(): MetalsGlobal = {
    if (_compiler == null) {
      _compiler = newCompiler()
    }
    _compiler.reporter.reset()
    _compiler
  }

  private def onCompilerJobQueue[T](
      thunk: () => T,
      token: CancelToken
  ): CompletableFuture[T] = {
    val result = new CompletableFuture[T]()
    jobs.submit(result, { () =>
      token.checkCanceled()
      Thread.interrupted() // clear interrupt bit
      result.complete(thunk())
      ()
    })

    // User cancelled task.
    token.onCancel.whenCompleteAsync((isCancelled, ex) => {
      if (isCancelled && !result.isDone()) {
        result.cancel(false)
      }
    }, ec)
    // Task has timed out, cancel this request and restart the compiler.
    sh.foreach { scheduler =>
      scheduler.schedule[Unit]({ () =>
        if (!result.isDone()) {
          result.cancel(false)
          restart()
        }
      }, config.timeoutDelay(), config.timeoutUnit())
    }

    result
  }
}
