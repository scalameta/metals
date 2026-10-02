package scala.meta.internal.metals.notebooks

import java.io.File
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageType

/**
 * Installs a Jupyter kernelspec for a given Scala version + classpath, using
 * Almond's own `launcher_3` tool (verified empirically against a real
 * install, not just docs: main class `almond.launcher.Launcher`, run via a
 * plain `java -cp` process rather than `cs launch`).
 *
 * Almond's launcher can only auto-detect "how do I relaunch myself" when
 * invoked through `cs launch`; invoked as a plain process like this, it
 * needs the real kernel-launch command spelled out via repeated `--arg`
 * flags, which becomes the resulting `kernel.json`'s `argv` verbatim. The
 * project's real classpath is threaded into that command via Almond's own
 * `--extra-class-path` (repeatable) flag — no predef script needed.
 */
object AlmondKernelInstaller {

  /**
   * Bumped from `0.14.5`: as of 0.15.0, `scala-kernel-api`/`scala-kernel`/
   * `scala-interpreter` publish once per *binary* Scala version (`_2.12`,
   * `_2.13`, `_3`) instead of once per *full* version (`_2.13.18`, …), so a
   * single Almond release now covers every patch of a supported line —
   * what previously forced a re-pin on every new Scala patch (`0.14.1` had
   * no `scala-kernel-api_2.13.18`, only `0.14.5` did).
   *
   * Trade-off: 0.15.0 also narrowed Scala 3 support to only the latest
   * release and the LTS (`3.9.0`/`3.3.8` at the time of writing) — `3.4.x`
   * through `3.8.x` aren't supported anymore, so a notebook on one of those
   * installs a kernel that won't actually start. The `launcher_3` artifact
   * this downloads and runs is, regardless, only ever published for Scala 3
   * — that's the launcher tool's own implementation language, unrelated to
   * the *target* notebook's Scala version, threaded through separately via
   * `--scala`.
   */
  private val almondVersion = "0.15.0"

  def install(
      languageClient: MetalsLanguageClient,
      javaHome: Option[String],
      cwd: AbsolutePath,
      scalaVersion: String,
      classpath: List[Path],
      kernelId: String,
      displayName: String,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      launcherClasspath <- Future {
        Embedded.downloadDependency("sh.almond", "launcher_3", almondVersion)
      }
      javaBin = JavaBinary(javaHome)
      launcherClasspathStr = launcherClasspath.mkString(File.pathSeparator)
      // The command Jupyter will actually run every time this kernel
      // starts; becomes the installed `kernel.json`'s `argv` verbatim
      // (`--connection-file {connection_file}` is appended by the
      // installer automatically, confirmed empirically).
      runCommand =
        List(
          javaBin,
          "-cp",
          launcherClasspathStr,
          "almond.launcher.Launcher",
          "--scala",
          scalaVersion,
        ) ++ classpath.flatMap(p => List("--extra-class-path", p.toString))
      installArgs =
        List(
          "-cp",
          launcherClasspathStr,
          "almond.launcher.Launcher",
          "--install",
          "--scala",
          scalaVersion,
          "--id",
          kernelId,
          "--display-name",
          displayName,
          "--force",
        ) ++ runCommand.flatMap(arg => List("--arg", arg))
      exitCode <- SystemProcess
        .run(
          javaBin :: installArgs,
          cwd,
          redirectErrorOutput = true,
          env = Map.empty,
        )
        .complete
    } yield
      if (exitCode == 0)
        languageClient.showMessage(
          MessageType.Info,
          s"Installed Jupyter kernel '$displayName'. Select it from your editor's kernel/Run picker to execute this notebook.",
        )
      else
        languageClient.showMessage(
          MessageType.Error,
          s"Failed to install Jupyter kernel '$displayName' (exit code $exitCode) — check the Metals log for details.",
        )
  }.recover { case NonFatal(e) =>
    scribe.error(s"failed to install Almond kernel '$kernelId'", e)
    languageClient.showMessage(
      MessageType.Error,
      s"Failed to install Jupyter kernel '$displayName': ${e.getMessage}",
    )

  }
}
