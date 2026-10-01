package scala.meta.internal.metals.mbt.importer

import java.util.concurrent.CancellationException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput

/**
 * `bazel fetch` for the external dependency labels of the imported targets,
 * so that the jars of a pinned rules_jvm_external hub exist under `external/`
 * before the Maven lock file is matched against it.
 */
object BazelFetch {

  def commandArgs(labels: Iterable[String]): List[String] =
    List("bazel", "fetch", "--keep_going") ++ labels.toList.distinct.sorted

  /**
   * Fetch problems are logged and the import continues with whatever is on
   * disk; only a cancelled fetch fails the returned future.
   */
  def externalDependencies(
      labels: Set[String],
      env: BazelQuery.Env,
      mbtJavaHome: Option[String] = None,
  )(implicit ec: ExecutionContext): Future[Unit] =
    if (labels.isEmpty) Future.unit
    else {
      scribe.info(
        s"bazel-mbt: fetching ${labels.size} external dependency label(s)"
      )
      env.shellRunner
        .run(
          "bazel-mbt-fetch",
          commandArgs(labels),
          env.projectRoot,
          redirectErrorOutput = false,
          mbtJavaHome.orElse(env.javaHome),
          processOut = ProcessOutput.Lines(scribe.debug(_)),
          processErr = scribe.warn(_),
        )
        .future
        .flatMap {
          case ExitCodes.Success => Future.unit
          case ExitCodes.Cancel =>
            Future.failed(
              new CancellationException("bazel-mbt: fetch cancelled")
            )
          case code =>
            scribe.warn(
              s"bazel-mbt: bazel fetch exited with code $code; " +
                "some external jars may be missing from the imported classpath"
            )
            Future.unit
        }
        .recover {
          case e if !e.isInstanceOf[CancellationException] =>
            scribe.warn("bazel-mbt: bazel fetch failed", e)
        }
    }
}
