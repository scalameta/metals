package scala.meta.internal.jpc

import scala.meta.internal.pc.CompilerWrapper
import scala.meta.internal.pc.ReporterAccess

class JavaMetalsCompilerWrapper(factory: () => JavaMetalsCompiler)
    extends CompilerWrapper[Unit, JavaMetalsCompiler] {
  @volatile private[this] var _compiler: JavaMetalsCompiler = _

  override def resetReporter(): Unit = ()
  override def reporterAccess: ReporterAccess[Unit] = new ReporterAccess[Unit] {
    override def reporter: Unit = ()
  }

  override def askShutdown(): Unit = {
    stop()
  }

  override def isAlive(): Boolean = _compiler != null

  override def stop(): Unit = {
    if (_compiler != null) {
      _compiler.close()
      _compiler = null
    }
  }

  override def compiler(): JavaMetalsCompiler = {
    if (_compiler == null) {
      _compiler = factory()
    }
    _compiler
  }

  override def presentationCompilerThread: Option[Thread] = None

}
