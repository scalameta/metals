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
    userConfig: () => UserConfiguration
) extends Cancelable {

  override def cancel(): Unit = {
    presentationCompilers.clear()
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
  def fetchSettings(
      dependency: Dependency,
      scalaVersion: String
  ): Fetch = {
    val repositories =
      Repository.defaults().asScala ++
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

    val resolutionParams = ResolutionParams
      .create()
      .forceVersions(
        List(
          Dependency.of(
            "org.scala-lang",
            "scala-library",
            scalaVersion
          ),
          Dependency.of(
            "org.scala-lang",
            "scala-compiler",
            scalaVersion
          ),
          Dependency.of(
            "org.scala-lang",
            "scala-reflect",
            scalaVersion
          )
        ).map(d => (d.getModule, d.getVersion)).toMap.asJava
      )

    Fetch
      .create()
      .addRepositories(
        repositories: _*
      )
      .withDependencies(
        dependency
      )
      .withResolutionParams(resolutionParams)
      .withMainArtifacts()
  }

  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val pc = Dependency.of(
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
    val jars = fetchSettings(dependency, info.getScalaVersion())
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
