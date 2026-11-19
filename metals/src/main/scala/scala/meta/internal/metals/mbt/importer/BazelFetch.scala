package scala.meta.internal.metals.mbt.importer

import java.util.concurrent.CancellationException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.process.ExitCodes
import scala.meta.internal.process.ProcessOutput
import scala.meta.internal.process.SystemProcess

/**
 * `bazel fetch` for the external dependency labels of the imported targets,
 * so that the jars of a pinned rules_jvm_external hub exist under `external/`
 * before the Maven lock file is matched against it.
 */
object BazelFetch {

  def commandArgs(labels: Iterable[String]): List[String] =
    List("bazel", "fetch", "--keep_going") ++ labels

  def repoCommandArgs(repos: Iterable[String]): List[String] =
    List("bazel", "fetch", "--force", "--keep_going") ++
      repos.map(r => if (r.startsWith("--repo=")) r else s"--repo=$r")

  /**
   * Sorted, distinct labels split into batches whose command line stays
   * below [[SystemProcess.processCmdCharLimit]].
   */
  def batches(
      labels: Iterable[String],
      limit: Int = SystemProcess.processCmdCharLimit,
  ): List[List[String]] =
    batchByCommandLength(labels, limit)(commandArgs)

  /**
   * Sorted, distinct Bzlmod repository names split into `--repo=` batches
   * whose command line stays below [[SystemProcess.processCmdCharLimit]].
   */
  def repoBatches(
      repos: Iterable[String],
      limit: Int = SystemProcess.processCmdCharLimit,
  ): List[List[String]] =
    batchByCommandLength(repos, limit)(repoCommandArgs)

  private def batchByCommandLength(
      items: Iterable[String],
      limit: Int,
  )(toCommandArgs: List[String] => List[String]): List[List[String]] =
    items.toList.distinct.sorted
      .foldLeft(List.empty[List[String]]) {
        case (batch :: done, item)
            if toCommandArgs(item :: batch).mkString(" ").length <= limit =>
          (item :: batch) :: done
        case (done, item) => List(item) :: done
      }
      .map(_.reverse)
      .reverse

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
      val all = batches(labels)
      scribe.info(
        s"bazel-mbt: fetching ${labels.size} external dependency label(s) in ${all.size} batch(es)"
      )
      all.foldLeft(Future.unit) { (previous, batch) =>
        previous.flatMap(_ => runFetch(commandArgs(batch), env, mbtJavaHome))
      }
    }

  /**
   * Force-fetch missing or GC-evicted Bzlmod per-artifact repositories
   * (`--repo=@@<canonicalRepoName>`) so dangling symlinks under `external/`
   * are repopulated before lockfile JAR paths are resolved.
   */
  def repositories(
      repos: Set[String],
      env: BazelQuery.Env,
      mbtJavaHome: Option[String] = None,
  )(implicit ec: ExecutionContext): Future[Unit] =
    if (repos.isEmpty) Future.unit
    else {
      val all = repoBatches(repos)
      scribe.info(
        s"bazel-mbt: fetching ${repos.size} missing/evicted artifact repo(s) in ${all.size} batch(es)"
      )
      all.foldLeft(Future.unit) { (previous, batch) =>
        previous.flatMap(_ =>
          runFetch(repoCommandArgs(batch), env, mbtJavaHome)
        )
      }
    }

  private def runFetch(
      args: List[String],
      env: BazelQuery.Env,
      mbtJavaHome: Option[String],
  )(implicit ec: ExecutionContext): Future[Unit] =
    env.shellRunner
      .run(
        "bazel-mbt-fetch",
        args,
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
