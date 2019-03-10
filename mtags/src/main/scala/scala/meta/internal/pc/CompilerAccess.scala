package scala.meta.internal.pc

import java.lang
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import java.util.logging.Level
import java.util.logging.Logger
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.meta.pc.CancelToken

/**
 * Manages the lifecycle of the compiler.
 *
 * - automatically restarts the compiler on expected crashes caused by macroparadise.
 * - handles cancellation via `Thread.interrupt()` to stop the compiler during typechecking.
 */
class CompilerAccess(
    sh: Option[ScheduledExecutorService],
    newCompiler: () => MetalsGlobal
)(implicit ec: ExecutionContext) {
  private val logger: Logger = Logger.getLogger(classOf[CompilerAccess].getName)
  private def isEmpty: Boolean = _compiler == null
  private def isDefined: Boolean = !isEmpty
  def reporter: StoreReporter =
    if (isEmpty) new StoreReporter()
    else _compiler.reporter.asInstanceOf[StoreReporter]
  def shutdown(): Unit = lock.synchronized {
    val compiler = _compiler
    if (compiler != null) {
      compiler.askShutdown()
      _compiler = null
      sh.foreach { scheduler =>
        scheduler.schedule(new Runnable {
          override def run(): Unit = {
            if (compiler.presentationCompilerThread.isAlive) {
              compiler.presentationCompilerThread.stop()
            }
          }
        }, 2, TimeUnit.SECONDS)
      }
    }
  }

  /**
   * Run the given thunk with unique access to the compiler instance.
   *
   * Will not run other requests in parallel.
   */
  def withCompiler[T](
      default: T,
      token: CancelToken
  )(thunk: MetalsGlobal => T): T = lock.synchronized {
    val thread = Thread.currentThread()
    Thread.interrupted() // clear interrupt flag
    val isFinished = new AtomicBoolean(false)
    token
      .onCancel()
      .whenComplete(new BiConsumer[java.lang.Boolean, Throwable] {
        override def accept(isCancelled: lang.Boolean, u: Throwable): Unit = {
          if (isCancelled && isFinished
              .compareAndSet(false, true) && isDefined) {
            _compiler.presentationCompilerThread.interrupt()
            if (thread != _compiler.presentationCompilerThread) {
              thread.interrupt()
            }
          }
        }
      })
    try withSharedCompiler(default)(thunk)
    finally isFinished.set(true)
  }

  /**
   * Run the given thunk with an unlocked compiler instance.
   *
   * May potentially run in parallel with other requests, use carefully.
   * Does not support cancellation.
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
        if (isParadiseRelated) {
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
    shutdown()
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
    shutdown()
  }
  private var _compiler: MetalsGlobal = _
  private val lock = new Object
  private def loadCompiler(): MetalsGlobal = {
    if (_compiler == null) {
      _compiler = newCompiler()
    }
    _compiler.reporter.reset()
    _compiler
  }
}
