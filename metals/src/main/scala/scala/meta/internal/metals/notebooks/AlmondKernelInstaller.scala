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
import scala.util.Try
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
 *
 * `kernelId` already encodes the Almond version (see
 * `NotebookKernelInstaller.kernelIdFor`), so a different version never
 * collides with, or gets mistaken for up to date against, another one —
 * only the classpath needs comparing here.
 */
object AlmondKernelInstaller {

  val defaultAlmondVersion = "0.15.0"

  def install(
      languageClient: MetalsLanguageClient,
      javaHome: Option[String],
      almondVersion: String,
      scalaVersion: String,
      classpath: List[Path],
      kernelId: String,
      displayName: String,
      notifyOnSuccess: Boolean = true,
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
      writeKernelSpec(kernelId, displayName, runCommand, classpath)
      if (notifyOnSuccess)
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

  def exists(kernelId: String): Boolean =
    Files.exists(userKernelsDir.resolve(kernelId).resolve("kernel.json"))

  private def writeKernelSpec(
      kernelId: String,
      displayName: String,
      runCommand: List[String],
      classpath: List[Path],
  ): Unit = {
    val dir = userKernelsDir.resolve(kernelId)
    if (Files.exists(dir)) deleteRecursively(dir)
    Files.createDirectories(dir)
    Files.write(
      dir.resolve("kernel.json"),
      kernelSpecJson(displayName, runCommand, classpath).toString.getBytes(
        StandardCharsets.UTF_8
      ),
    )
  }

  /**
   * True only if a kernel is installed for `kernelId` AND it was installed
   * against the same classpath as now — otherwise a stale install (the
   * project's classpath changed since) looks identical to "not installed"
   * to callers deciding whether to offer a (re)install.
   */
  def isUpToDate(kernelId: String, classpath: List[Path]): Boolean = {
    val file = userKernelsDir.resolve(kernelId).resolve("kernel.json")
    val result = Try(ujson.read(Files.readString(file))).toOption
      .exists(isUpToDateJson(_, classpath))
    if (!result)
      scribe.debug(
        s"kernel '$kernelId' not up to date; file=$file exists=${Files.exists(file)} classpath=${classpath.map(_.toString)}"
      )
    result
  }

  def isUpToDateJson(kernelJson: ujson.Value, classpath: List[Path]): Boolean =
    Try {
      val stored = kernelJson("metadata")("classpath").arr.map(_.str).toList
      val current = classpath.map(_.toString)
      if (stored != current)
        scribe.debug(s"stored classpath=$stored current classpath=$current")
      stored == current
    }.getOrElse(false)

  // https://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs
  def kernelSpecJson(
      displayName: String,
      runCommand: List[String],
      classpath: List[Path],
  ): ujson.Obj =
    ujson.Obj(
      "argv" -> (runCommand ++ List("--connection-file", "{connection_file}")),
      "display_name" -> displayName,
      "language" -> "scala",
      "env" -> ujson.Obj(),
      "metadata" -> ujson.Obj(
        "classpath" -> classpath.map(_.toString)
      ),
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
