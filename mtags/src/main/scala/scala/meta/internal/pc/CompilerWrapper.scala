package scala.meta.internal.pc

trait CompilerWrapper[Reporter, Compiler] {

  def resetReporter(): Unit

  def reporterAccess: ReporterAccess[Reporter]

  def askShutdown(): Unit

  def isAlive(): Boolean

  def stop(): Unit

  def compiler(): Compiler

  def presentationCompilerThread: Option[Thread]
}
