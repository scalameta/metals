package scala.meta.internal.metals.notebooks

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.clients.language.MetalsLanguageClient

import org.eclipse.lsp4j.MessageType

/**
 * Installs a Jupyter kernelspec for a given Scala version + classpath.
 *
 * Writes `kernel.json` directly instead of shelling out to Almond's
 * `almond.launcher.Launcher --install`: per its source
 * (`almond.kernel.install.Install.installIn`), `--install` does nothing
 * beyond resolving the kernels directory, force-overwriting an existing
 * one, and serializing this same JSON.
 */
object AlmondKernelInstaller {

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
      // Written into kernel.json's argv; Jupyter runs this to start the kernel.
      runCommand = List(
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

  // https://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs
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
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.list(path))(_.forEach(deleteRecursively))
    Files.deleteIfExists(path)
  }

  // Mirrors almond.kernel.util.JupyterPaths.userPath.
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
