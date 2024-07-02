package scala.meta.internal.metals.debug.server

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.debugadapter.CancelableFuture
import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.JavaRuntime
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.UnmanagedEntry

class MainClassDebugAdapter(
    root: AbsolutePath,
    mainClass: ScalaMainClass,
    project: DebugeeProject,
    userJavaHome: Option[String],
)(implicit ec: ExecutionContext)
    extends MetalsDebuggee() {

  override def modules: Seq[Module] = project.modules

  override def libraries: Seq[Library] = project.libraries

  override def unmanagedEntries: Seq[UnmanagedEntry] = project.unmanagedEntries

  protected def scalaVersionOpt: Option[String] = project.scalaVersion

  val javaRuntime: Option[JavaRuntime] =
    JdkSources
      .defaultJavaHome(userJavaHome)
      .flatMap(path => JavaRuntime(path.toNIO))
      .headOption

  def name: String =
    s"${getClass.getSimpleName}(${project.name}, ${mainClass.getClassName()})"
  def run(listener: DebuggeeListener): CancelableFuture[Unit] =
    Run.runMain(
      root = root,
      classPath = classPath,
      userJavaHome = userJavaHome,
      className = mainClass.getClassName,
      args = mainClass.getArguments().asScala.toList,
      jvmOptions = mainClass.getJvmOptions.asScala.toList,
      evnVariables = mainClass.getEnvironmentVariables().asScala.toList,
      logger = new Logger(listener),
    )
}
