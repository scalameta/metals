package scala.meta.internal.pc

import scala.meta.pc.VirtualFileParams

trait CompilerWrapper[Reporter, Compiler] {

  def resetReporter(): Unit

  def reporterAccess: ReporterAccess[Reporter]

  def askShutdown(): Unit

  def isAlive(): Boolean

  def stop(): Unit

  def compiler(changeFiles: OutlineFiles): Compiler

  def compiler(): Compiler = compiler(OutlineFiles.empty)

  def presentationCompilerThread: Option[Thread]

}

case class OutlineFiles(
    files: List[VirtualFileParams],
    firstCompileSubstitute: Boolean = false
)

object OutlineFiles {
  def empty = OutlineFiles(Nil)
}
