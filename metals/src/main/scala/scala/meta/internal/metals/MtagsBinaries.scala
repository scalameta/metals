package scala.meta.internal.metals

import java.nio.file.Path

import scala.meta.internal.mtags

sealed trait MtagsBinaries {
  def scalaVersion: String
}
object MtagsBinaries {
  case object BuildIn extends MtagsBinaries {
    val scalaVersion = mtags.BuildInfo.scalaCompilerVersion
  }
  case class Artifacts(scalaVersion: String, jars: List[Path])
      extends MtagsBinaries

  def isBuildIn(scalaVersion: String): Boolean =
    ScalaVersions.dropVendorSuffix(scalaVersion) == BuildIn.scalaVersion
}
