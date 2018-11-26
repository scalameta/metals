package scala.meta.internal.metals

import com.geirsson.coursiersmall
import java.net.URLClassLoader
import java.nio.file.Files
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.meta.io.AbsolutePath
import scala.util.control.NonFatal

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - bloop.py
 * - ch.epfl.scala:bloop-frontend
 */
final class Embedded(icons: Icons, statusBar: StatusBar) extends Cancelable {

  override def cancel(): Unit = {
    bloopJars.foreach(_.close())
  }

  /**
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because
   * we can't rely on `sbt` resolving correctly when using system processes, at least
   * it failed on Windows when I tried it.
   */
  lazy val sbtLauncher: AbsolutePath = {
    val embeddedLauncher = this.getClass.getResourceAsStream("/sbt-launch.jar")
    val out = Files.createTempDirectory("metals").resolve("sbt-launch.jar")
    out.toFile.deleteOnExit()
    Files.copy(embeddedLauncher, out)
    AbsolutePath(out)
  }

  /**
   * Fetches jars for bloop-frontend and creates a new orphan classloader.
   */
  lazy val bloopJars: Option[URLClassLoader] = {
    val promise = Promise[Unit]()
    statusBar.trackFuture(s"${icons.sync}Downloading Bloop", promise.future)
    try {
      Some(Embedded.newBloopClassloader())
    } catch {
      case NonFatal(e) =>
        scribe.error("Failed to classload bloop, compilation will not work", e)
        None
    } finally {
      promise.trySuccess(())
    }
  }

  /**
   * Returns local path to a `bloop.py` script that we can call as `python bloop.py`.
   *
   * We don't `sys.process("bloop", ...)` directly because that requires bloop to be
   * available on the PATH of the forked process and that didn't work while testing
   * on Windows (even if `bloop` worked fine in the git bash).
   */
  lazy val bloopPy: AbsolutePath = {
    val embeddedBloopClient = this.getClass.getResourceAsStream("/bloop.py")
    val out = Files.createTempDirectory("metals").resolve("bloop.py")
    out.toFile.deleteOnExit()
    Files.copy(embeddedBloopClient, out)
    AbsolutePath(out)
  }
}

object Embedded {
  private def newBloopClassloader(): URLClassLoader = {
    val settings = new coursiersmall.Settings()
      .withTtl(Some(Duration.Inf))
      .withDependencies(
        List(
          new coursiersmall.Dependency(
            "ch.epfl.scala",
            "bloop-frontend_2.12",
            BuildInfo.bloopVersion
          )
        )
      )
      .addRepositories(
        List(
          coursiersmall.Repository.SonatypeReleases,
          new coursiersmall.Repository.Maven(
            "https://dl.bintray.com/scalacenter/releases"
          )
        )
      )
    val jars = coursiersmall.CoursierSmall.fetch(settings)
    // Don't make Bloop classloader a child or our classloader.
    val parent: ClassLoader = null
    val classloader =
      new URLClassLoader(jars.iterator.map(_.toUri.toURL).toArray, parent)
    classloader
  }
}
