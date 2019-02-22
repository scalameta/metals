package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import com.geirsson.coursiersmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.ServiceLoader
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.util.control.NonFatal

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - bloop.py
 * - ch.epfl.scala:bloop-frontend
 */
final class Embedded(
    icons: Icons,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration,
    newBloopClassloader: () => URLClassLoader
) extends Cancelable {

  override def cancel(): Unit = {
    presentationCompilers.clear()
  }

  /**
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because
   * we can't rely on `sbt` resolving correctly when using system processes, at least
   * it failed on Windows when I tried it.
   */
  lazy val embeddedSbtLauncher: AbsolutePath = {
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
    statusBar.trackBlockingTask(s"${icons.sync}Downloading Bloop") {
      try {
        Some(newBloopClassloader())
      } catch {
        case NonFatal(e) =>
          scribe.error(
            "Failed to classload bloop, compilation will not work",
            e
          )
          None
      }
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

  private val presentationCompilers: TrieMap[String, URLClassLoader] =
    TrieMap.empty
  def presentationCompiler(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): PresentationCompiler = {
    val classloader = presentationCompilers.getOrElseUpdate(
      info.getScalaVersion,
      statusBar.trackBlockingTask(
        s"${icons.sync}Downloading presentation compiler"
      ) {
        Embedded.newPresentationCompilerClassLoader(info, scalac)
      }
    )
    val services =
      ServiceLoader.load(classOf[PresentationCompiler], classloader).iterator()
    if (services.hasNext) services.next()
    else throw new NoSuchElementException(classOf[PresentationCompiler].getName)
  }
}

object Embedded {
  def downloadSettings(dependency: Dependency): Settings =
    new coursiersmall.Settings()
      .withTtl(Some(Duration.Inf))
      .withDependencies(List(dependency))
  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val pc = new Dependency(
      "org.scalameta",
      s"pc_${info.getScalaVersion}",
      BuildInfo.metalsVersion
    )
    val needsFullClasspath = !scalac.isSemanticdbEnabled
    val dependency =
      if (needsFullClasspath) pc
      else pc.withTransitive(false)
    val settings = downloadSettings(dependency)
    val jars = coursiersmall.CoursierSmall.fetch(settings)
    val scalaJars = info.getJars.asScala.map(_.toAbsolutePath.toNIO)
    val semanticdbJars =
      if (needsFullClasspath) Nil
      else {
        scalac.getOptions.asScala.collect {
          case opt
              if opt.startsWith("-Xplugin:") &&
                opt.contains("semanticdb-scalac") =>
            Paths.get(opt.stripPrefix("-Xplugin:"))
        }
      }
    val allJars = Iterator(jars, scalaJars, semanticdbJars).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }

  def newBloopClassloader(): URLClassLoader = {
    val settings = downloadSettings(
      new Dependency(
        "ch.epfl.scala",
        "bloop-frontend_2.12",
        BuildInfo.bloopVersion
      )
    ).addRepositories(
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
