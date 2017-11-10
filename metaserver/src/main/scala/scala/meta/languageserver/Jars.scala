package scala.meta.languageserver

import java.io.OutputStreamWriter
import java.io.PrintStream
import coursier._
import org.langmeta.io.AbsolutePath

case class ModuleID(organization: String, name: String, version: String) {
  def toCoursier: Dependency = Dependency(Module(organization, name), version)
  override def toString: String = s"$organization:$name:$version"
}
object Jars {
  def fetch(
      org: String,
      artifact: String,
      version: String,
      out: PrintStream,
      sources: Boolean = false
  ): List[AbsolutePath] =
    fetch(ModuleID(org, artifact, version) :: Nil, out, sources)

  def fetch(
      modules: Iterable[ModuleID],
      out: PrintStream,
      sources: Boolean
  ): List[AbsolutePath] = {
    val classifier = if (sources) "sources" :: Nil else Nil
    val res = Resolution(modules.toIterator.map(_.toCoursier).toSet)
    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )
    val logger =
      new TermDisplay(new OutputStreamWriter(out), fallbackMode = true)
    logger.init()
    val fetch = Fetch.from(repositories, Cache.fetch(logger = Some(logger)))
    val resolution = res.process.run(fetch).unsafePerformSync
    val errors = resolution.metadataErrors
    if (errors.nonEmpty) {
      sys.error(errors.mkString("\n"))
    }
    val artifacts: Seq[Artifact] =
      if (sources) {
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
    val failures = localArtifacts.collect { case Left(e) => e }
    if (failures.nonEmpty) {
      sys.error(failures.mkString("\n"))
    } else {
      val jars = localArtifacts.collect {
        case Right(file) if file.getName.endsWith(".jar") =>
          file
      }
      logger.stop()
      jars.map(AbsolutePath(_))
    }
  }
}
