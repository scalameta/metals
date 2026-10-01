package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput

/**
 * `bazel fetch` for the external dependency labels of the imported targets.
 *
 * The Maven lock file is matched against the jars Bazel materialized under
 * `external/`. On a cold output base those jars do not exist yet: a pinned
 * rules_jvm_external hub only creates its per-artifact repositories while
 * Bazel loads the targets that need them. Fetching the external labels first
 * guarantees the jars are on disk before the lock file is resolved, so a
 * fresh checkout imports with a complete classpath instead of an empty
 * `dependencyModules` list.
 */
object BazelFetch {

  def commandArgs(patternFile: Path): List[String] =
    List(
      "bazel",
      "fetch",
      "--keep_going",
      s"--target_pattern_file=$patternFile",
    )

  /** One target pattern per line, as `--target_pattern_file` expects. */
  def patternFileContent(labels: Iterable[String]): String =
    labels.toList.distinct.sorted.mkString("", "\n", "\n")

  /**
   * Fetches the external repositories the labels depend on. Never fails:
   * problems are logged and the import continues with whatever is on disk.
   */
  def externalDependencies(
      labels: Set[String],
      env: BazelQuery.Env,
      mbtJavaHome: Option[String] = None,
  )(implicit ec: ExecutionContext): Future[Unit] =
    if (labels.isEmpty) Future.unit
    else {
      val patternFile = Files.createTempFile("metals-bazel-fetch-", ".txt")
      patternFile.toFile.deleteOnExit()
      patternFile.writeText(patternFileContent(labels))
      scribe.info(
        s"bazel-mbt: fetching ${labels.size} external dependency label(s)"
      )
      env.shellRunner
        .run(
          "bazel-mbt-fetch",
          commandArgs(patternFile),
          env.projectRoot,
          redirectErrorOutput = false,
          mbtJavaHome.orElse(env.javaHome),
          processOut = ProcessOutput.Lines(scribe.debug(_)),
          processErr = scribe.warn(_),
        )
        .future
        .map {
          case ExitCodes.Success => ()
          case code =>
            scribe.warn(
              s"bazel-mbt: bazel fetch exited with code $code; " +
                "some external jars may be missing from the imported classpath"
            )
        }
        .recover { case e =>
          scribe.warn("bazel-mbt: bazel fetch failed", e)
        }
        .andThen { case _ => Try(Files.deleteIfExists(patternFile)) }
    }
}
