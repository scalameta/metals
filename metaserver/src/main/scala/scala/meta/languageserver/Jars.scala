package scala.meta.languageserver

import java.io.OutputStreamWriter
import java.io.PrintStream
import coursier._
import org.langmeta.io.AbsolutePath

object Jars {
  // TODO(olafur) this method should return a monix.Task.
  def fetch(
      org: String,
      artifact: String,
      version: String,
      out: PrintStream,
      sources: Boolean = false
  ): List[AbsolutePath] = {
    val classifier = if (sources) "sources" :: Nil else Nil

    val res = Resolution(
      Set(
        Dependency(Module(org, artifact), version)
      )
    )
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
    val localArtifacts = scalaz.concurrent.Task
      .gatherUnordered(
        resolution
          .dependencyClassifiersArtifacts(classifier)
          .map(_._2)
          .map(artifact => Cache.file(artifact).run)
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
