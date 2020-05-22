package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths

import scala.collection.JavaConverters._

import scala.meta.internal.metals.BuildInfo

import coursierapi.Dependency
import coursierapi.Fetch
import ujson.Value

case class PantsScalaPlatform(
    scalaBinaryVersion: String,
    compilerClasspath: Seq[Path]
)

object PantsScalaPlatform {
  def fromJson(output: Value): PantsScalaPlatform = {
    val (scalaVersion, compilerClasspath) =
      output.obj.get(PantsKeys.scalaPlatform) match {
        case Some(scalaPlatform) =>
          scalaPlatform.obj(PantsKeys.scalaVersion).str ->
            scalaPlatform
              .obj(PantsKeys.compilerClasspath)
              .arr
              .map(path => Paths.get(path.str))
        case None =>
          "2.12" ->
            fetchScalaCompilerClasspath(BuildInfo.scala212)
      }
    PantsScalaPlatform(scalaVersion, compilerClasspath)
  }
  private def fetchScalaCompilerClasspath(scalaVersion: String): Seq[Path] =
    Fetch
      .create()
      .withDependencies(
        Dependency.of("org.scala-lang", "scala-compiler", scalaVersion)
      )
      .fetch()
      .asScala
      .map(_.toPath())
      .toList
}
