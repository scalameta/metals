package scala.meta.internal.metals.notebook

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

import scala.collection.concurrent.TrieMap
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
 *
 * @param dataDir the Jupyter data directory, see [[AlmondKernelInstaller.jupyterDataDir]],
 *                resolved on use so an invalid one only fails the install
 * @param launcherClasspath resolves the Almond launcher for a version
 */
final class AlmondKernelInstaller private[notebook] (
    dataDir: () => Path,
    launcherClasspath: String => List[Path],
) {
  import AlmondKernelInstaller._

  /** Resolves the Almond launcher, so it may download it. */
  def kernelSpec(
      javaHome: Option[String],
      almondVersion: String,
      scalaVersion: String,
      classpath: List[Path],
      displayName: String,
  ): ujson.Obj =
    kernelSpecJson(
      displayName,
      argv(
        JavaBinary(javaHome),
        launcherClasspath(almondVersion),
        scalaVersion,
        classpath,
      ),
    )

  def exists(kernelId: String): Boolean =
    Files.exists(kernelJson(kernelId))

  /**
   * True if `kernelId` is installed with this spec, so a changed classpath,
   * Java binary, Scala version or launcher makes it stale. BSP gives no
   * ordering guarantee across separate calls, so a reordered classpath
   * alone doesn't.
   */
  def isInstalled(kernelId: String, spec: ujson.Value): Boolean =
    readSpec(kernelId).exists(sameSpec(_, spec))

  /**
   * Stages the kernel outside the kernels directory, so Jupyter never lists
   * a half-written or leftover one, and swaps it in only once it's written.
   * The previous kernel is moved aside rather than deleted, and restored if
   * the swap fails. If a concurrent install of the same `kernelId` swaps its
   * own in first, that one is kept.
   */
  def install(kernelId: String, spec: ujson.Value): Unit = {
    // Resolved once, so staging and the kernel are on the same filesystem.
    val base = dataDir()
    val dir = kernelsDir(base).resolve(kernelId)
    val staging = Files.createDirectories(stagingPath(base, kernelId))
    try {
      Files.write(
        staging.resolve("kernel.json"),
        spec.toString.getBytes(StandardCharsets.UTF_8),
      )
      Files.createDirectories(kernelsDir(base))
      val previous = moveAside(dir, stagingPath(base, kernelId), kernelId)
      try Files.move(staging, dir)
      catch {
        case _: FileAlreadyExistsException
            if Files.exists(dir.resolve("kernel.json")) =>
        case NonFatal(e) =>
          previous.foreach { p =>
            if (Try(Files.move(p, dir)).isFailure)
              scribe.warn(
                s"could not restore Almond kernel '$kernelId' from $p"
              )
          }
          throw e
      }
      // The new kernel is in place, and Jupyter doesn't see a leftover here.
      previous.foreach(p => Try(deleteRecursively(p)))
    } finally deleteRecursively(staging)
  }

  private def readSpec(kernelId: String): Option[ujson.Value] =
    Try(ujson.read(Files.readString(kernelJson(kernelId)))).toOption

  private def moveAside(
      dir: Path,
      aside: Path,
      kernelId: String,
  ): Option[Path] =
    if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.move(dir, aside)
        Some(aside)
      } catch {
        case _: NoSuchFileException =>
          scribe.warn(
            s"Almond kernel '$kernelId' was moved aside by a concurrent install"
          )
          None
      }
    } else None

  // Next to the kernels directory, so moving out of it stays on one
  // filesystem.
  private def stagingPath(base: Path, kernelId: String): Path =
    base
      .resolve("metals-kernel-staging")
      .resolve(s"$kernelId-${UUID.randomUUID()}")

  private def kernelJson(kernelId: String): Path =
    kernelsDir(dataDir()).resolve(kernelId).resolve("kernel.json")

  // Not AbsolutePath.deleteRecursively(): must guarantee it never
  // follows a symlink out of the kernel directory.
  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      Using.resource(Files.list(path))(_.forEach(deleteRecursively))
    Files.deleteIfExists(path)
  }

  private def kernelsDir(base: Path): Path = base.resolve("kernels")
}

object AlmondKernelInstaller {

  val defaultAlmondVersion = "0.15.0"

  private val extraClassPath = "--extra-class-path"
  private val launcherClasspaths = TrieMap.empty[String, List[Path]]

  lazy val default: AlmondKernelInstaller = new AlmondKernelInstaller(
    () =>
      jupyterDataDir(
        // System.getenv, unlike sys.env, ignores case on Windows like
        // Python's os.environ.
        name => Option(System.getenv(name)),
        Paths.get(sys.props("user.home")),
        Properties.isMac,
        Properties.isWin,
      ),
    // Resolved on first use, so it may download the launcher.
    almondVersion =>
      launcherClasspaths.getOrElseUpdate(
        almondVersion,
        Embedded.downloadDependency("sh.almond", "launcher_3", almondVersion),
      ),
  )

  // Keeps the classpath order, it decides which of two same-named classes
  // the kernel loads.
  private[notebook] def argv(
      javaBin: String,
      launcherClasspath: List[Path],
      scalaVersion: String,
      classpath: List[Path],
  ): List[String] = List(
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
      .flatMap(p => List(extraClassPath, p)) ++
    List("--connection-file", "{connection_file}")

  // https://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs
  private[notebook] def kernelSpecJson(
      displayName: String,
      argv: List[String],
  ): ujson.Obj = ujson.Obj(
    "argv" -> argv,
    "display_name" -> displayName,
    "language" -> "scala",
    "env" -> ujson.Obj(),
  )

  private[notebook] def sameSpec(a: ujson.Value, b: ujson.Value): Boolean =
    withSortedClasspath(a) == withSortedClasspath(b)

  private def withSortedClasspath(spec: ujson.Value): ujson.Value = try {
    val (prefix, rest) =
      spec("argv").arr.map(_.str).toList.span(_ != extraClassPath)
    val (classpath, suffix) =
      rest.grouped(2).toList.span(_.headOption.contains(extraClassPath))
    val sorted = ujson.Obj.from(spec.obj)
    sorted("argv") =
      prefix ++ classpath.sortBy(_.last).flatten ++ suffix.flatten
    sorted
  } catch {
    case NonFatal(_) => spec
  }

  // Mirrors almond.kernel.util.JupyterPaths.userPath / Jupyter's own
  // jupyter_core.paths.jupyter_data_dir. Skipping JUPYTER_DATA_DIR or
  // XDG_DATA_HOME would install a kernel Jupyter's own picker never sees.
  private[notebook] def jupyterDataDir(
      env: String => Option[String],
      home: Path,
      isMac: Boolean,
      isWin: Boolean,
  ): Path = {
    // Like Jupyter, treats an empty variable as unset.
    def dir(name: String) = env(name).filter(_.nonEmpty).map(Paths.get(_))
    dir("JUPYTER_DATA_DIR").getOrElse {
      if (isMac)
        home.resolve("Library").resolve("Jupyter")
      else if (isWin)
        dir("APPDATA")
          .getOrElse(home.resolve("AppData").resolve("Roaming"))
          .resolve("jupyter")
      else
        dir("XDG_DATA_HOME")
          .getOrElse(home.resolve(".local").resolve("share"))
          .resolve("jupyter")
    }
  }
}
