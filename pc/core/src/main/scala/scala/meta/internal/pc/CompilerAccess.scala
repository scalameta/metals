package scala.meta.internal.pc

import java.util.logging.Level
import java.util.logging.Logger
import scala.tools.nsc.reporters.StoreReporter
import scala.util.control.NonFatal

class CompilerAccess(logger: Logger, newCompiler: () => MetalsGlobal) {
  def isEmpty: Boolean = _compiler == null
  def isDefined: Boolean = !isEmpty
  def reporter: StoreReporter =
    if (isEmpty) new StoreReporter()
    else _compiler.reporter.asInstanceOf[StoreReporter]
  def shutdown(): Unit = {
    if (_compiler != null) {
      _compiler.askShutdown()
      _compiler = null
    }
  }
  def withCompiler[T](default: T)(thunk: MetalsGlobal => T): T = {
    def retryWithCleanCompiler(cause: String): T = {
      shutdown()
      logger.log(
        Level.INFO,
        s"compiler crashed due to $cause, retrying with new compiler instance."
      )
      try thunk(loadCompiler())
      catch {
        case NonFatal(e) =>
          handleError(e)
          default
      }
    }
    lock.synchronized {
      try thunk(loadCompiler())
      catch {
        case NonFatal(e) =>
          val isParadiseRelated = e.getStackTrace
            .exists(_.getClassName.startsWith("org.scalamacros"))
          if (isParadiseRelated) {
            // Testing shows that the scalamacro paradise plugin tends to crash
            // easily in long-running sessions. We retry with a fresh compiler
            // to see if that fixes the issue. This is a hacky solution that
            // introduces significant overhead for creating a new compiler instance.
            // A better long-term solution is to fix the paradise plugin implementation
            // to be  more resilient in long-running sessions.
            retryWithCleanCompiler(
              "the org.scalamacros:paradise compiler plugin"
            )
          } else {
            handleError(e)
            default
          }
      }
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
