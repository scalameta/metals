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
    lock.synchronized {
      try thunk(loadCompiler())
      catch {
        case NonFatal(e) =>
          CompilerThrowable.trimStackTrace(e)
          logger.log(Level.SEVERE, e.getMessage, e)
          shutdown()
          default
      }
    }
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
