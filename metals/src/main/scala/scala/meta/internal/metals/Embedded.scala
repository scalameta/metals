package scala.meta.internal.metals

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader

import scala.collection.concurrent.TrieMap

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.worksheets.MdocClassLoader
import scala.meta.io.Classpath
import scala.meta.pc.PresentationCompiler

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
      scalaVersion: String,
      classpath: Seq[Path]
  ): PresentationCompiler = {
    val classloader = presentationCompilers.getOrElseUpdate(
      ScalaVersions.dropVendorSuffix(scalaVersion),
      statusBar.trackSlowTask("Preparing presentation compiler") {
        Embedded.newPresentationCompilerClassLoader(scalaVersion, classpath)
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
    val resolutionParams = ResolutionParams
      .create()

    /* note(@tgodzik) we add an exclusion so that the mdoc classlaoder does not try to
     * load coursierapi.Logger and instead will use the already loaded one
     */
    resolutionParams.addExclusion("io.get-coursier", "interface")
    val jars = downloadMdoc(scalaVersion, scalaBinaryVersion, resolutionParams)

    val parent = new MdocClassLoader(this.getClass.getClassLoader)

    // Full mdoc classpath seems to be causing some issue with akka
    // We want to keep a minimal set of jars needed here
    val runtimeClasspath = jars.filter { path =>
      val pathString = path.toString
      pathString.contains("scala-lang") ||
      pathString.contains("fansi") ||
      pathString.contains("pprint") ||
      pathString.contains("sourcecode") ||
      pathString.contains("mdoc") ||
      pathString.contains("scalameta") ||
      pathString.contains("metaconfig") ||
      pathString.contains("diffutils")
    }
    val urls = runtimeClasspath.iterator.map(_.toUri().toURL()).toArray
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

  private def scala3Dependency(scalaVersion: String): Dependency = {
    val binaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    if (binaryVersion.startsWith("3")) {
      Dependency.of(
        "org.scala-lang",
        s"scala3-library_$binaryVersion",
        scalaVersion
      )
    } else {
      Dependency.of(
        "ch.epfl.lamp",
        s"dotty-library_$binaryVersion",
        scalaVersion
      )
    }
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
  ): Dependency = {
    Dependency.of(
      "org.scalameta",
      s"mdoc_${scalaBinaryVersion}",
      BuildInfo.mdocVersion
    )
  }

  private def semanticdbScalacDependency(scalaVersion: String): Dependency =
    Dependency.of(
      "org.scalameta",
      s"semanticdb-scalac_$scalaVersion",
      BuildInfo.scalametaVersion
    )

  private def downloadDependency(
      dep: Dependency,
      scalaVersion: String,
      classfiers: Seq[String] = Seq.empty,
      resolution: ResolutionParams = ResolutionParams.create()
  ): List[Path] =
    fetchSettings(dep, scalaVersion)
      .addClassifiers(classfiers: _*)
      .withResolutionParams(resolution)
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

  def downloadScala3Sources(scalaVersion: String): List[Path] =
    downloadDependency(
      scala3Dependency(scalaVersion),
      scalaVersion,
      classfiers = Seq("sources")
    )

  def downloadSemanticdbScalac(scalaVersion: String): List[Path] =
    downloadDependency(semanticdbScalacDependency(scalaVersion), scalaVersion)
  def downloadMtags(scalaVersion: String): List[Path] =
    downloadDependency(mtagsDependency(scalaVersion), scalaVersion)

  def downloadMdoc(
      scalaVersion: String,
      scalaBinaryVersion: String,
      resolutionParams: ResolutionParams = ResolutionParams.create()
  ): List[Path] =
    downloadDependency(
      mdocDependency(scalaVersion, scalaBinaryVersion),
      scalaVersion,
      resolution = resolutionParams
    )

  def organizeImportRule(scalaBinaryVersion: String): List[Path] = {
    val dep = Dependency.of(
      "com.github.liancheng",
      s"organize-imports_$scalaBinaryVersion",
      BuildInfo.organizeImportVersion
    )
    downloadDependency(dep, scalaBinaryVersion)
  }

  def newPresentationCompilerClassLoader(
      fullScalaVersion: String,
      classpath: Seq[Path]
  ): URLClassLoader = {
    val scalaVersion = ScalaVersions
      .dropVendorSuffix(fullScalaVersion)
    val dep = mtagsDependency(scalaVersion)
    val jars = fetchSettings(dep, fullScalaVersion)
      .fetch()
      .asScala
      .map(_.toPath)
    val allJars = Iterator(jars, classpath).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }

  def toClassLoader(
      classpath: Classpath,
      classLoader: ClassLoader
  ): URLClassLoader = {
    val urls = classpath.entries.map(_.toNIO.toUri.toURL).toArray
    new URLClassLoader(urls, classLoader)
  }

  /**
   * Provides scala3-library dependency for stadalone PC and mdoc instances
   */
  def scalaLibrary(scalaVersion: String): List[Path] = {
    val dependency =
      if (ScalaVersions.isScala3Version(scalaVersion))
        scala3Dependency(scalaVersion)
      else
        scalaDependency(scalaVersion)
    downloadDependency(dependency, scalaVersion)
  }

}
