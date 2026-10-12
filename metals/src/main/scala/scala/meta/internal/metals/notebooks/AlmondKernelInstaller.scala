package scala.meta.internal.metals.notebooks

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import scala.util.Properties
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary

/**
 * Builds and installs Jupyter kernelspecs that run Almond.
 *
 * Writes `kernel.json` directly instead of shelling out to Almond's
 * `almond.launcher.Launcher --install`: per its source
 * (`almond.kernel.install.Install.installIn`), `--install` does nothing
 * beyond resolving the kernels directory, force-overwriting an existing
 * one, and serializing this same JSON.
 */
object AlmondKernelInstaller {

  val defaultAlmondVersion = "0.15.0"

  /** Resolves the Almond launcher, so it may download it. */
  def kernelSpec(
      javaHome: Option[String],
      almondVersion: String,
      scalaVersion: String,
      classpath: List[Path],
      displayName: String,
  ): ujson.Obj = {
    val launcherClasspath =
      Embedded.downloadDependency("sh.almond", "launcher_3", almondVersion)
    kernelSpecJson(
      displayName,
      argv(JavaBinary(javaHome), launcherClasspath, scalaVersion, classpath),
    )
  }

  // BSP gives no ordering guarantee across separate calls, so the classpath
  // is sorted to keep an unchanged one from looking stale.
  def argv(
      javaBin: String,
      launcherClasspath: List[Path],
      scalaVersion: String,
      classpath: List[Path],
  ): List[String] =
    List(
      javaBin,
      "-cp",
      launcherClasspath.mkString(File.pathSeparator),
      "almond.launcher.Launcher",
      "--scala",
      scalaVersion,
    ) ++
      classpath
        .map(_.toString)
        .distinct
        .sorted
        .flatMap(p => List("--extra-class-path", p)) ++
      List("--connection-file", "{connection_file}")

  // https://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs
  def kernelSpecJson(displayName: String, argv: List[String]): ujson.Obj =
    ujson.Obj(
      "argv" -> argv,
      "display_name" -> displayName,
      "language" -> "scala",
      "env" -> ujson.Obj(),
    )

  def exists(kernelId: String): Boolean =
    Files.exists(kernelJson(kernelId))

  /**
   * True if `kernelId` is installed with exactly this spec, so a changed
   * classpath, Java binary, Scala version or launcher makes it stale.
   */
  def isInstalled(kernelId: String, spec: ujson.Value): Boolean =
    Try(ujson.read(Files.readString(kernelJson(kernelId)))).toOption
      .contains(spec)

  /**
   * Stages the kernel outside the kernels directory, so Jupyter never lists
   * a half-written or leftover one, and swaps it in only once it's written.
   * If a concurrent install of the same `kernelId` swaps its own in first,
   * that one is kept.
   */
  def install(kernelId: String, spec: ujson.Value): Unit = {
    val dir = kernelsDir.resolve(kernelId)
    val staging = stagingDir.resolve(s"$kernelId-${UUID.randomUUID()}")
    Files.createDirectories(staging)
    try {
      Files.write(
        staging.resolve("kernel.json"),
        spec.toString.getBytes(StandardCharsets.UTF_8),
      )
      Files.createDirectories(kernelsDir)
      if (Files.exists(dir)) deleteRecursively(dir)
      Files.move(staging, dir)
    } catch {
      case _: FileAlreadyExistsException =>
        deleteRecursively(staging)
      case NonFatal(e) =>
        deleteRecursively(staging)
        throw e
    }
  }

  private def kernelJson(kernelId: String): Path =
    kernelsDir.resolve(kernelId).resolve("kernel.json")

  // Not AbsolutePath.deleteRecursively(): must guarantee it never
  // follows a symlink out of the kernel directory.
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.list(path))(_.forEach(deleteRecursively))
    Files.deleteIfExists(path)
  }

  private def kernelsDir: Path = dataDir.resolve("kernels")

  // Next to the kernels directory, so moving out of it stays on one
  // filesystem.
  private def stagingDir: Path = dataDir.resolve("metals-kernel-staging")

  // Mirrors almond.kernel.util.JupyterPaths.userPath / Jupyter's own
  // jupyter_core.paths.jupyter_data_dir. Skipping JUPYTER_DATA_DIR or
  // XDG_DATA_HOME would install a kernel Jupyter's own picker never sees.
  private def dataDir: Path = {
    val home = Paths.get(sys.props("user.home"))
    sys.env
      .get("JUPYTER_DATA_DIR")
      .map(Paths.get(_))
      .getOrElse {
        if (Properties.isMac)
          home.resolve("Library").resolve("Jupyter")
        else if (Properties.isWin)
          sys.env
            .get("APPDATA")
            .map(Paths.get(_))
            .getOrElse(home.resolve("AppData").resolve("Roaming"))
            .resolve("jupyter")
        else
          sys.env
            .get("XDG_DATA_HOME")
            .map(Paths.get(_))
            .getOrElse(home.resolve(".local").resolve("share"))
            .resolve("jupyter")
      }
  }
}
