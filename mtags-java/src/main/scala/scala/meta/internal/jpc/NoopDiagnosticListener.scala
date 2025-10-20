package scala.meta.internal.jpc

import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

object NoopDiagnosticListener extends DiagnosticListener[JavaFileObject] {

  // ignore errors since presentation compiler will have a lot of transient ones
  override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = ()
}
