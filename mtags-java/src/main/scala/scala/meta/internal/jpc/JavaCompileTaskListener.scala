package scala.meta.internal.jpc

import java.io.StringWriter
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

import scala.collection.mutable.ArrayBuffer

class JavaCompileTaskListener extends DiagnosticListener[JavaFileObject] {
  val sout = new StringWriter()
  val diagnostics: ArrayBuffer[Diagnostic[_ <: JavaFileObject]] =
    ArrayBuffer.empty[Diagnostic[_ <: JavaFileObject]]
  override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = {
    diagnostics += diagnostic
  }
}
