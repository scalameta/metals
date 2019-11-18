package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import java.net.URLClassLoader
import java.nio.file.Paths
import java.util.ServiceLoader
import scala.collection.concurrent.TrieMap
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository
import coursierapi.ResolutionParams
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
      statusBar.trackSlowTask("Preparing presentation compiler") {
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
  lazy val repositories: List[Repository] =
    Repository.defaults().asScala.toList ++
      List(
        Repository.central(),
        Repository.ivy2Local(),
        MavenRepository.of(
          "https://oss.sonatype.org/content/repositories/releases/"
        ),
        MavenRepository.of(
          "https://oss.sonatype.org/content/repositories/snapshots/"
        )
      )

  def newMdocClassLoader(info: ScalaBuildTarget): URLClassLoader = {
    val mdoc = Dependency.of(
      "org.scalameta",
      s"mdoc_${info.getScalaBinaryVersion()}",
      BuildInfo.mdocVersion
    )
    val settings = downloadSettings(mdoc, info.getScalaVersion())
    val jars = fetchSettings(mdoc, info.getScalaVersion()).fetch()
    val parent =
      new MdocClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(jars.map(_.toUri().toURL()).toArray, parent)
  }

  def fetchSettings(
      dep: Dependency,
      scalaVersion: String
  ): Fetch = {
    val resolutionParams = ResolutionParams
      .create()
      .forceVersions(
        List(
          Dependency.of("org.scala-lang", "scala-library", scalaVersion),
          Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
          Dependency.of("org.scala-lang", "scala-reflect", scalaVersion)
        ).map(d => (d.getModule, d.getVersion)).toMap.asJava
      )

    Fetch
      .create()
      .addRepositories(repositories: _*)
      .withDependencies(dep)
      .withResolutionParams(resolutionParams)
      .withMainArtifacts()
  }

  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val scalaVersion = ScalaVersions
      .dropVendorSuffix(info.getScalaVersion)
    val pc = Dependency.of(
      "org.scalameta",
      s"mtags_$scalaVersion",
      BuildInfo.metalsVersion
    )
    val semanticdbJars = scalac.getOptions.asScala.collect {
      case opt
          if opt.startsWith("-Xplugin:") &&
            opt.contains("semanticdb-scalac") &&
            opt.contains(BuildInfo.semanticdbVersion) =>
        Paths.get(opt.stripPrefix("-Xplugin:"))
    }
    val dep =
      if (semanticdbJars.isEmpty) pc
      else pc.withTransitive(false)
    val jars = fetchSettings(dep, info.getScalaVersion())
      .fetch()
      .asScala
      .map(_.toPath)

    val scalaJars = info.getJars.asScala.map(_.toAbsolutePath.toNIO)
    val allJars = Iterator(jars, scalaJars, semanticdbJars).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }
}
