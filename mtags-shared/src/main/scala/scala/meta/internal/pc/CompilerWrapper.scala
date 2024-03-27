package scala.meta.internal.pc

import scala.meta.pc.VirtualFileParams

trait CompilerWrapper[Reporter, Compiler] {

  def resetReporter(): Unit

  def reporterAccess: ReporterAccess[Reporter]

  def askShutdown(): Unit

  def isAlive(): Boolean

  def stop(): Unit

  def compiler(params: VirtualFileParams): Compiler = compiler()

  def compiler(): Compiler

  def presentationCompilerThread: Option[Thread]

}
