package scala.meta.languageserver

import java.io.File
import java.io.OutputStreamWriter
import java.io.PrintStream
import coursier._

object Jars {
  def fetch(
      org: String,
      artifact: String,
      version: String,
      out: PrintStream
  ): List[File] = {
    val start = Resolution(Set(Dependency(Module(org, artifact), version)))
    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )
    val logger =
      new TermDisplay(new OutputStreamWriter(out), fallbackMode = true)
    logger.init()
    val fetch = Fetch.from(repositories, Cache.fetch(logger = Some(logger)))
    val resolution = start.process.run(fetch).unsafePerformSync
    val errors = resolution.metadataErrors
    if (errors.nonEmpty) {
      sys.error(errors.mkString("\n"))
    }
    val localArtifacts = scalaz.concurrent.Task
      .gatherUnordered(
        resolution.artifacts.map(Cache.file(_).run)
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
      jars
    }
  }

}
