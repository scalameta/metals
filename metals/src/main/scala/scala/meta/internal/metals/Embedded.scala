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
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.io.AbsolutePath
import scala.meta.pc.PresentationCompiler
import scala.util.control.NonFatal
import com.geirsson.coursiersmall.CoursierSmall

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
   * Fetches jars for bloop-frontend and creates a new orphan classloader.
   */
  lazy val bloopJars: Option[URLClassLoader] = {
    statusBar.trackSlowTask(s"${icons.sync}Downloading Bloop") {
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
      ScalaVersions.dropVendorSuffix(info.getScalaVersion),
      statusBar.trackSlowTask("Downloading presentation compiler") {
        Embedded.newPresentationCompilerClassLoader(info, scalac)
      }
    )
    val services =
      ServiceLoader.load(classOf[PresentationCompiler], classloader).iterator()
    if (services.hasNext) services.next()
    else {
      // NOTE(olafur): ServiceLoader doesn't find the presentation compiler service
      // on Appveyor for some reason, I'm unable to reproduce on my computer. Here below
      // we fallback to manual classloading.
      val cls =
        classloader.loadClass(classOf[ScalaPresentationCompiler].getName)
      val ctor = cls.getDeclaredConstructor()
      ctor.setAccessible(true)
      ctor.newInstance().asInstanceOf[PresentationCompiler]
    }
  }
}

object Embedded {
  def downloadSettings(dependency: Dependency): Settings =
    new coursiersmall.Settings()
      .withTtl(Some(Duration.Inf))
      .withDependencies(List(dependency))
      .addRepositories(
        List(
          coursiersmall.Repository.SonatypeReleases,
          coursiersmall.Repository.SonatypeSnapshots
        )
      )

  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val pc = new Dependency(
      "org.scalameta",
      s"mtags_${ScalaVersions.dropVendorSuffix(info.getScalaVersion)}",
      BuildInfo.metalsVersion
    )
    val needsFullClasspath = !scalac.isSemanticdbEnabled
    val dependency =
      if (needsFullClasspath) pc
      else pc.withTransitive(false)
    val settings = downloadSettings(dependency)
    val jars = CoursierSmall.fetch(settings)
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

  def newBloopClassloader(
      bloopVersion: String = BuildInfo.bloopVersion
  ): URLClassLoader = {
    val settings = downloadSettings(
      new Dependency(
        "ch.epfl.scala",
        "bloop-frontend_2.12",
        bloopVersion
      ),
      new Dependency(
        "org.scala-lang",
        "scala-library",
        BuildInfo.scala212
      ),
      new Dependency(
        "org.scala-lang",
        "scala-compiler",
        BuildInfo.scala212
      ),
      new Dependency(
        "org.scala-lang",
        "scala-reflect",
        BuildInfo.scala212
      )
    ).addRepositories(
      List(
        new coursiersmall.Repository.Maven(
          "https://dl.bintray.com/scalacenter/releases"
        )
      )
    )
    val jars = CoursierSmall.fetch(settings)
    // Don't make Bloop classloader a child or our classloader.
    val parent: ClassLoader = null
    val classloader =
      new URLClassLoader(jars.iterator.map(_.toUri.toURL).toArray, parent)
    classloader
  }
}
