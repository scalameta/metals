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
   * Almond's `kernel_2.13` artifact was never published past this release
   * (nothing newer exists on Maven Central) — pinning to it is what lets
   * this install a kernel for both Scala 2.13 and Scala 3 notebooks. The
   * `launcher_3` artifact this downloads and runs is only ever published
   * for Scala 3 regardless of Almond version — that's the launcher tool's
   * own implementation language, unrelated to the *target* notebook's Scala
   * version, which is threaded through separately via `--scala`.
   */
  private val almondVersion = "0.14.1"

  def install(
      languageClient: MetalsLanguageClient,
      javaHome: Option[String],
      cwd: AbsolutePath,
      scalaVersion: String,
      classpath: List[Path],
      kernelId: String,
      displayName: String,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    Future {
      Embedded.downloadDependency("sh.almond", "launcher_3", almondVersion)
    }.flatMap { launcherClasspath =>
      val javaBin = JavaBinary(javaHome)
      val launcherClasspathStr =
        launcherClasspath.mkString(File.pathSeparator)

      // The command Jupyter will actually run every time this kernel
      // starts; becomes the installed `kernel.json`'s `argv` verbatim
      // (`--connection-file {connection_file}` is appended by the
      // installer automatically, confirmed empirically).
      val runCommand =
        List(
          javaBin,
          "-cp",
          launcherClasspathStr,
          "almond.launcher.Launcher",
          "--scala",
          scalaVersion,
        ) ++ classpath.flatMap(p => List("--extra-class-path", p.toString))

      val installArgs =
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

      SystemProcess
        .run(
          javaBin :: installArgs,
          cwd,
          redirectErrorOutput = true,
          env = Map.empty,
        )
        .complete
    }
  }.map { exitCode =>
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
