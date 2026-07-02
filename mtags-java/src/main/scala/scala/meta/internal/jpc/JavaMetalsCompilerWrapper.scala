package scala.meta.internal.jpc

import scala.meta.internal.pc.CompilerWrapper
import scala.meta.internal.pc.ReporterAccess

class JavaMetalsCompilerWrapper(factory: () => JavaMetalsCompiler)
    extends CompilerWrapper[Unit, JavaMetalsCompiler] {
  @volatile private[this] var _compiler: JavaMetalsCompiler = _

  private[this] val lock = new Object
  // Number of requests currently using the compiler. Guarded by `lock`.
  private[this] var inUse = 0
  // Set when a shutdown was requested while a request was still running.
  // Guarded by `lock`.
  private[this] var stopRequested = false

  override def resetReporter(): Unit = ()
  override def reporterAccess: ReporterAccess[Unit] = new ReporterAccess[Unit] {
    override def reporter: Unit = ()
  }

  override def askShutdown(): Unit = {
    stop()
  }

  override def isAlive(): Boolean = _compiler != null

  /**
   * Closes the underlying compiler, but only when no request is currently using
   * it.
   *
   * Unlike the Scala presentation compiler, javac runs its work directly on the
   * compiler job thread rather than on a dedicated thread that can honor a
   * shutdown request at a safe point. Restarts happen very frequently (after
   * every successful build compile, on save, on proto changes, etc.) and from
   * background threads, so closing the shared `JavaFileManager` synchronously
   * here would tear the compiler down in the middle of an in-flight request
   * (e.g. an extract-method analysis), which surfaces as
   * `NullPointerException: ... "this.enter" is null`.
   *
   * If a request is running we defer the close until it finishes (see
   * [[withInUse]]).
   */
  override def stop(): Unit = lock.synchronized {
    if (inUse > 0) {
      stopRequested = true
    } else {
      doClose()
    }
  }

  private def doClose(): Unit = {
    if (_compiler != null) {
      _compiler.close()
      _compiler = null
    }
    stopRequested = false
  }

  /**
   * Runs `thunk` while marking the compiler as in use so that a concurrent
   * shutdown does not close it mid-flight. If a shutdown was requested while the
   * request was running, the compiler is closed once the last request finishes.
   */
  def withInUse[T](thunk: => T): T = {
    lock.synchronized { inUse += 1 }
    try thunk
    finally {
      lock.synchronized {
        inUse -= 1
        if (inUse <= 0 && stopRequested) doClose()
      }
    }
  }

  override def compiler(): JavaMetalsCompiler = lock.synchronized {
    if (_compiler == null) {
      _compiler = factory()
    }
    _compiler
  }

  override def presentationCompilerThread: Option[Thread] = None

}
