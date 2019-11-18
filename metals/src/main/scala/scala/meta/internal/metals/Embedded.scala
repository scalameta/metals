package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import com.geirsson.coursiersmall
import com.geirsson.coursiersmall.Dependency
import com.geirsson.coursiersmall.Settings
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.ServiceLoader
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler
import com.geirsson.coursiersmall.CoursierSmall
import scala.meta.internal.worksheets.MdocClassLoader
import mdoc.interfaces.Mdoc

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - bloop.py
 * - ch.epfl.scala:bloop-frontend
 * - mdoc
 */
final class Embedded(
    icons: Icons,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
) extends Cancelable {

  override def cancel(): Unit = {
    presentationCompilers.clear()
    mdocs.clear()
  }

  private val mdocs: TrieMap[String, URLClassLoader] =
    TrieMap.empty
  def mdoc(info: ScalaBuildTarget): Mdoc = {
    val classloader = mdocs.getOrElseUpdate(
      info.getScalaBinaryVersion(),
      statusBar.trackSlowTask("Preparing worksheets") {
        Embedded.newMdocClassLoader(info)
      }
    )
    serviceLoader(
      classOf[Mdoc],
      "mdoc.internal.worksheets.Mdoc",
      classloader
    )
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
    serviceLoader(
      classOf[PresentationCompiler],
      classOf[ScalaPresentationCompiler].getName(),
      classloader
    )
  }

  private def serviceLoader[T](
      cls: Class[T],
      className: String,
      classloader: URLClassLoader
  ): T = {
    val services = ServiceLoader.load(cls, classloader).iterator()
    if (services.hasNext) services.next()
    else {
      // NOTE(olafur): ServiceLoader doesn't find the service on Appveyor for
      // some reason, I'm unable to reproduce on my computer. Here below we
      // fallback to manual classloading.
      val cls = classloader.loadClass(className)
      val ctor = cls.getDeclaredConstructor()
      ctor.setAccessible(true)
      ctor.newInstance().asInstanceOf[T]
    }

  }
}

object Embedded {
  def downloadSettings(
      dependency: Dependency,
      scalaVersion: String
  ): Settings =
    new coursiersmall.Settings()
      .withTtl(Some(Duration.Inf))
      .withDependencies(List(dependency))
      .addRepositories(
        List(
          coursiersmall.Repository.SonatypeReleases,
          coursiersmall.Repository.SonatypeSnapshots
        )
      )
      .withForceVersions(
        List(
          new Dependency(
            "org.scala-lang",
            "scala-library",
            scalaVersion
          ),
          new Dependency(
            "org.scala-lang",
            "scala-compiler",
            scalaVersion
          ),
          new Dependency(
            "org.scala-lang",
            "scala-reflect",
            scalaVersion
          )
        )
      )

  def newMdocClassLoader(info: ScalaBuildTarget): URLClassLoader = {
    val mdoc = new Dependency(
      "org.scalameta",
      s"mdoc_${info.getScalaBinaryVersion()}",
      BuildInfo.mdocVersion
    )
    val settings = downloadSettings(mdoc, info.getScalaVersion())
    val jars = CoursierSmall.fetch(settings)
    val parent =
      new MdocClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(jars.map(_.toUri().toURL()).toArray, parent)
  }

  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val pc = new Dependency(
      "org.scalameta",
      s"mtags_${ScalaVersions.dropVendorSuffix(info.getScalaVersion)}",
      BuildInfo.metalsVersion
    )
    val semanticdbJars = scalac.getOptions.asScala.collect {
      case opt
          if opt.startsWith("-Xplugin:") &&
            opt.contains("semanticdb-scalac") &&
            opt.contains(BuildInfo.semanticdbVersion) =>
        Paths.get(opt.stripPrefix("-Xplugin:"))
    }
    val dependency =
      if (semanticdbJars.isEmpty) pc
      else pc.withTransitive(false)
    val settings = downloadSettings(dependency, info.getScalaVersion())
    val jars = CoursierSmall.fetch(settings)
    val scalaJars = info.getJars.asScala.map(_.toAbsolutePath.toNIO)
    val allJars = Iterator(jars, scalaJars, semanticdbJars).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }
}
