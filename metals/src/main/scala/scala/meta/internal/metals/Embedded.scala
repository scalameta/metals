package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.worksheets.MdocClassLoader
import scala.meta.pc.PresentationCompiler

import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.MavenRepository
import coursierapi.Repository
import coursierapi.ResolutionParams
import mdoc.interfaces.Mdoc

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - mdoc
 */
final class Embedded(
    icons: Icons,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration
) extends Cancelable {

  private val mdocs: TrieMap[String, URLClassLoader] =
    TrieMap.empty
  private val presentationCompilers: TrieMap[String, URLClassLoader] =
    TrieMap.empty

  override def cancel(): Unit = {
    presentationCompilers.clear()
    mdocs.clear()
  }

  def mdoc(scalaVersion: String, scalaBinaryVersion: String): Mdoc = {
    val classloader = mdocs.getOrElseUpdate(
      scalaBinaryVersion,
      statusBar.trackSlowTask("Preparing worksheets") {
        Embedded.newMdocClassLoader(scalaVersion, scalaBinaryVersion)
      }
    )
    serviceLoader(
      classOf[Mdoc],
      "mdoc.internal.worksheets.Mdoc",
      classloader
    )
  }

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
          "https://oss.sonatype.org/content/repositories/public/"
        ),
        MavenRepository.of(
          "https://oss.sonatype.org/content/repositories/snapshots/"
        )
      )

  def newMdocClassLoader(
      scalaVersion: String,
      scalaBinaryVersion: String
  ): URLClassLoader = {
    val jars = downloadMdoc(scalaVersion, scalaBinaryVersion)
    val parent = new MdocClassLoader(this.getClass.getClassLoader)
    val urls = jars.iterator.map(_.toUri().toURL()).toArray
    new URLClassLoader(urls, parent)
  }

  def fetchSettings(
      dep: Dependency,
      scalaVersion: String
  ): Fetch = {

    val resolutionParams = ResolutionParams
      .create()

    if (!ScalaVersions.isScala3Version(scalaVersion))
      resolutionParams.forceVersions(
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
  private def scalaDependency(scalaVersion: String): Dependency =
    Dependency.of("org.scala-lang", "scala-library", scalaVersion)

  private def dottyDependency(scalaVersion: String): Dependency = {
    val binaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    Dependency.of("ch.epfl.lamp", s"dotty-library_$binaryVersion", scalaVersion)
  }

  private def mtagsDependency(scalaVersion: String): Dependency =
    Dependency.of(
      "org.scalameta",
      s"mtags_$scalaVersion",
      BuildInfo.metalsVersion
    )

  private def mdocDependency(
      scalaVersion: String,
      scalaBinaryVersion: String
  ): Dependency =
    Dependency.of(
      "org.scalameta",
      s"mdoc_${scalaBinaryVersion}",
      BuildInfo.mdocVersion
    )

  private def semanticdbScalacDependency(scalaVersion: String): Dependency =
    Dependency.of(
      "org.scalameta",
      s"semanticdb-scalac_$scalaVersion",
      BuildInfo.scalametaVersion
    )

  private def downloadDependency(
      dep: Dependency,
      scalaVersion: String,
      classfiers: Seq[String] = Seq.empty
  ): List[Path] =
    fetchSettings(dep, scalaVersion)
      .addClassifiers(classfiers: _*)
      .fetch()
      .asScala
      .toList
      .map(_.toPath())

  def downloadScalaSources(scalaVersion: String): List[Path] =
    downloadDependency(
      scalaDependency(scalaVersion),
      scalaVersion,
      classfiers = Seq("sources")
    )

  def downloadDottySources(scalaVersion: String): List[Path] =
    downloadDependency(
      dottyDependency(scalaVersion),
      scalaVersion,
      classfiers = Seq("sources")
    )

  def downloadSemanticdbScalac(scalaVersion: String): List[Path] =
    downloadDependency(semanticdbScalacDependency(scalaVersion), scalaVersion)
  def downloadMtags(scalaVersion: String): List[Path] =
    downloadDependency(mtagsDependency(scalaVersion), scalaVersion)
  def downloadMdoc(
      scalaVersion: String,
      scalaBinaryVersion: String
  ): List[Path] =
    downloadDependency(
      mdocDependency(scalaVersion, scalaBinaryVersion),
      scalaVersion
    )

  def newPresentationCompilerClassLoader(
      info: ScalaBuildTarget,
      scalac: ScalacOptionsItem
  ): URLClassLoader = {
    val scalaVersion = ScalaVersions
      .dropVendorSuffix(info.getScalaVersion)
    val dep = mtagsDependency(scalaVersion)
    val jars = fetchSettings(dep, info.getScalaVersion())
      .fetch()
      .asScala
      .map(_.toPath)
    val scalaJars = info.getJars.asScala.map(_.toAbsolutePath.toNIO)
    val allJars = Iterator(jars, scalaJars).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }

}
