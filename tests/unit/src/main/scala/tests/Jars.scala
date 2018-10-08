package tests

import coursier._
import java.io.OutputStreamWriter
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
    val classifier = if (fetchSourceJars) "sources" :: Nil else Nil
    val res = Resolution(modules.toIterator.map(_.toCoursier).toSet)
    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )
    val term =
      new TermDisplay(new OutputStreamWriter(out), fallbackMode = true)
    term.init()
    val fetch = Fetch.from(repositories, Cache.fetch(logger = Some(term)))
    val resolution = res.process.run(fetch).unsafePerformSync
    val errors = resolution.metadataErrors
    if (errors.nonEmpty) {
//      sys.error(errors.mkString("\n"))
    }

    val artifacts: Seq[Artifact] =
      if (fetchSourceJars) {
        resolution
          .dependencyClassifiersArtifacts(classifier)
          .map(_._2)
      } else resolution.artifacts
    val localArtifacts = scalaz.concurrent.Task
      .gatherUnordered(
        artifacts.map(artifact => Cache.file(artifact).run)
      )
      .unsafePerformSync
      .map(_.toEither)
    val jars = localArtifacts.flatMap {
      case Left(e) =>
        if (fetchSourceJars) {
          // There is no need to fail fast here if we are fetching source jars.
          scribe.error(e.describe)
          Nil
        } else {
          throw new IllegalArgumentException(e.describe)
        }
      case Right(jar) if jar.getName.endsWith(".jar") =>
       AbsolutePath(jar) :: Nil
      case _ => Nil
    }
    term.stop()
    jars
  }
}
