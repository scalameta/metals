package tests

import coursier._
import java.io.PrintStream
import scala.meta.io.AbsolutePath

case class ModuleID(organization: String, name: String, version: String) {
  def toCoursier: Dependency = Dependency(Module(organization, name), version)
  override def toString: String = s"$organization:$name:$version"
}

object ModuleID {
  def scalaReflect(scalaVersion: String): ModuleID =
    ModuleID("org.scala-lang", "scala-reflect", scalaVersion)
  def fromString(string: String): List[ModuleID] = {
    string
      .split(";")
      .iterator
      .flatMap { moduleId =>
        moduleId.split(":") match {
          case Array(org, name, rev) =>
            ModuleID(org, name, rev) :: Nil
          case _ => Nil
        }
      }
      .toList
  }
}

object Jars {
  def fetch(
      org: String,
      artifact: String,
      version: String,
      out: PrintStream = System.out,
      // If true, fetches the -sources.jar files instead of regular jar with classfiles.
      fetchSourceJars: Boolean = false
  ): List[AbsolutePath] =
    fetch(ModuleID(org, artifact, version) :: Nil, out, fetchSourceJars)

  def fetch(
      modules: Iterable[ModuleID],
      out: PrintStream,
      fetchSourceJars: Boolean
  ): List[AbsolutePath] = {
    Nil
  }
}
