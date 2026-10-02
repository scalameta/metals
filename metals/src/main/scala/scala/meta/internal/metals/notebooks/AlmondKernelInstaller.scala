package scala.meta.internal.metals.notebooks

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageType

/**
 * Installs a Jupyter kernelspec for a given Scala version + classpath by
 * writing `kernel.json` directly, rather than shelling out to Almond's own
 * `almond.launcher.Launcher --install`.
 *
 * Per Almond's own source (`almond.kernel.install.Install.installIn`),
 * `--install` does nothing beyond resolving the Jupyter kernels directory,
 * force-deleting an existing kernel dir, and serializing the exact same
 * JSON this class builds (plus an optional branding logo, skipped here) —
 * no Scala-version validation or dependency pre-resolution happens at
 * install time either way, so hand-writing it loses nothing functional,
 * and removes a dependency on Almond's own CLI flags staying compatible
 * across version bumps (what already forced a re-pin once, see
 * `almondVersion` below).
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
      // starts, written into kernel.json's `argv` verbatim (with
      // `--connection-file {connection_file}` appended below, the same way
      // Almond's own installer does).
      runCommand =
        List(
          javaBin,
          "-cp",
          launcherClasspathStr,
          "almond.launcher.Launcher",
          "--scala",
          scalaVersion,
        ) ++ classpath.flatMap(p => List("--extra-class-path", p.toString))
    } yield {
      writeKernelSpec(kernelId, displayName, runCommand)
      languageClient.showMessage(
        MessageType.Info,
        s"Installed Jupyter kernel '$displayName'. Select it from your editor's kernel/Run picker to execute this notebook.",
      )
    }
  }.recover { case NonFatal(e) =>
    scribe.error(s"failed to install Almond kernel '$kernelId'", e)
    languageClient.showMessage(
      MessageType.Error,
      s"Failed to install Jupyter kernel '$displayName': ${e.getMessage}",
    )
  }

  private def writeKernelSpec(
      kernelId: String,
      displayName: String,
      runCommand: List[String],
  ): Unit = {
    val dir = userKernelsDir.resolve(kernelId)
    if (Files.exists(dir)) deleteRecursively(dir)
    Files.createDirectories(dir)
    Files.write(
      dir.resolve("kernel.json"),
      kernelSpecJson(displayName, runCommand).toString.getBytes(
        StandardCharsets.UTF_8
      ),
    )
  }

  // See http://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs
  // for the kernel.json shape; mirrors `almond.protocol.KernelSpec`.
  def kernelSpecJson(
      displayName: String,
      runCommand: List[String],
  ): ujson.Obj =
    ujson.Obj(
      "argv" -> (runCommand ++ List("--connection-file", "{connection_file}")),
      "display_name" -> displayName,
      "language" -> "scala",
      "env" -> ujson.Obj(),
    )

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path))
      Files.list(path).forEach(deleteRecursively)
    Files.deleteIfExists(path)
  }

  // Mirrors `almond.kernel.util.JupyterPaths.userPath`, so a kernel we
  // install lands exactly where Almond's own launcher would look for/
  // install one.
  private def userKernelsDir: Path = {
    val home = Paths.get(sys.props("user.home"))
    if (Properties.isMac)
      home.resolve("Library").resolve("Jupyter").resolve("kernels")
    else if (Properties.isWin)
      Paths.get(sys.env("APPDATA")).resolve("jupyter").resolve("kernels")
    else
      home
        .resolve(".local")
        .resolve("share")
        .resolve("jupyter")
        .resolve("kernels")
  }
}
