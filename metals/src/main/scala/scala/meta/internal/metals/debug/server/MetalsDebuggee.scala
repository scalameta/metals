package scala.meta.internal.metals.debug.server

import java.io.Closeable

import scala.meta.internal.BuildInfo
import scala.meta.internal.metals.JdkSources

import ch.epfl.scala.debugadapter.Debuggee
import ch.epfl.scala.debugadapter.JavaRuntime
import ch.epfl.scala.debugadapter.Library
import ch.epfl.scala.debugadapter.Module
import ch.epfl.scala.debugadapter.ScalaVersion
import ch.epfl.scala.debugadapter.UnmanagedEntry

abstract class MetalsDebuggee(
    project: DebugeeProject,
    userJavaHome: Option[String],
) extends Debuggee {

  override val scalaVersion: ScalaVersion = ScalaVersion(
    project.scalaVersion.getOrElse(BuildInfo.version)
  )

  override def modules: Seq[Module] = project.modules
  override def libraries: Seq[Library] = project.libraries
  override def unmanagedEntries: Seq[UnmanagedEntry] = project.unmanagedEntries

  override val javaRuntime: Option[JavaRuntime] =
    JdkSources
      .defaultJavaHome(userJavaHome)
      .flatMap(path => JavaRuntime(path.toNIO))
      .headOption

  override def observeClassUpdates(
      onClassUpdate: Seq[String] => Unit
  ): Closeable = {
    // Hot code reload is not supported in Metals
    () => {}
  }

}
