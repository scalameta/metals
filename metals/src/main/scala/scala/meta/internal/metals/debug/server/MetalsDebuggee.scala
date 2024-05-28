package scala.meta.internal.metals.debug.server

import java.io.Closeable

import scala.meta.internal.BuildInfo

import ch.epfl.scala.debugadapter.Debuggee
import ch.epfl.scala.debugadapter.ScalaVersion

abstract class MetalsDebuggee extends Debuggee {
  protected def scalaVersionOpt: Option[String]
  override val scalaVersion: ScalaVersion = ScalaVersion(
    scalaVersionOpt.getOrElse(BuildInfo.version)
  )

  override def observeClassUpdates(
      onClassUpdate: Seq[String] => Unit
  ): Closeable = {
    // Hot code reload is not supported in Metals
    () => {}
  }

}
