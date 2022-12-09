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
    statusBar: StatusBar
) extends Cancelable {

  private val mdocs: TrieMap[String, URLClassLoader] =
    TrieMap.empty
  private val presentationCompilers: TrieMap[String, URLClassLoader] =
    TrieMap.empty

  override def cancel(): Unit = {
    presentationCompilers.clear()
    mdocs.clear()
  }

  def mdoc(scalaVersion: String): Mdoc = {
    val isScala3 = ScalaVersions.isScala3Version(scalaVersion)
    val scalaBinaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    val scalaVersionKey = if (isScala3) scalaVersion else scalaBinaryVersion
    val resolveSpecificVersionCompiler =
      if (isScala3) Some(scalaVersion) else None
    val classloader = mdocs.getOrElseUpdate(
      scalaVersionKey,
      statusBar.trackSlowTask("Preparing worksheets") {
        newMdocClassLoader(scalaBinaryVersion, resolveSpecificVersionCompiler)
      },
    )
    serviceLoader(
      classOf[Mdoc],
      "mdoc.internal.worksheets.Mdoc",
      classloader,
    )
  }

  def presentationCompiler(
      mtags: MtagsBinaries.Artifacts,
      classpath: Seq[Path],
  ): PresentationCompiler = {
    val classloader = presentationCompilers.getOrElseUpdate(
      ScalaVersions.dropVendorSuffix(mtags.scalaVersion),
      newPresentationCompilerClassLoader(mtags, classpath),
    )
    serviceLoader(
      classOf[PresentationCompiler],
      classOf[ScalaPresentationCompiler].getName(),
      classloader,
    )
  }

  private def serviceLoader[T](
      cls: Class[T],
      className: String,
      classloader: URLClassLoader,
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

  private def newMdocClassLoader(
      scalaBinaryVersion: String,
      scalaVersion: Option[String],
  ): URLClassLoader = {
    val resolutionParams = ResolutionParams
      .create()

    /* note(@tgodzik) we add an exclusion so that the mdoc classlaoder does not try to
     * load coursierapi.Logger and instead will use the already loaded one
     */
    resolutionParams.addExclusion("io.get-coursier", "interface")

    /**
     * For scala 3 the code is backwards compatible, but mdoc itself depends on
     * specific Scala version, which is the earliest we need to support.
     * This trick makes sure that we are able to run worksheets on later versions
     * by forcing mdoc to use that specific compiler version.
     *
     * This should work as long as the compiler interfaces we are using do not change.
     *
     * It would probably be best to use stable interfaces from the compiler.
     */
    val fullResolution = scalaVersion match {
      case None => resolutionParams
      case Some(version) =>
        resolutionParams.forceVersions(
          List(
            Dependency.of("org.scala-lang", "scala3-library_3", version),
            Dependency.of("org.scala-lang", "scala3-compiler_3", version),
            Dependency.of("org.scala-lang", "tasty-core_3", version),
          ).map(d => (d.getModule, d.getVersion)).toMap.asJava
        )
    }
    val jars =
      Embedded.downloadMdoc(
        scalaBinaryVersion,
        Some(fullResolution),
      )

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

  private def newPresentationCompilerClassLoader(
      mtags: MtagsBinaries.Artifacts,
      classpath: Seq[Path],
  ): URLClassLoader = {
    val allJars = Iterator(mtags.jars, classpath).flatten
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
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
        ),
      )

  def fetchSettings(
      dep: Dependency,
      scalaVersion: Option[String],
      resolution: Option[ResolutionParams] = None,
  ): Fetch = {

    val resolutionParams = resolution.getOrElse(ResolutionParams.create())

    scalaVersion.foreach { scalaVersion =>
      if (!ScalaVersions.isScala3Version(scalaVersion))
        resolutionParams.forceVersions(
          List(
            Dependency.of("org.scala-lang", "scala-library", scalaVersion),
            Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
            Dependency.of("org.scala-lang", "scala-reflect", scalaVersion),
          ).map(d => (d.getModule, d.getVersion)).toMap.asJava
        )
    }

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
        scalaVersion,
      )
    } else {
      Dependency.of(
        "ch.epfl.lamp",
        s"dotty-library_$binaryVersion",
        scalaVersion,
      )
    }
  }

  private def mtagsDependency(scalaVersion: String): Dependency =
    Dependency.of(
      "org.scalameta",
      s"mtags_$scalaVersion",
      BuildInfo.metalsVersion,
    )

  private def mdocDependency(
      scalaBinaryVersion: String
  ): Dependency = {
    Dependency.of(
      "org.scalameta",
      s"mdoc_${scalaBinaryVersion}",
      if (scalaBinaryVersion == "2.11") "2.2.24" else BuildInfo.mdocVersion,
    )
  }

  private def semanticdbScalacDependency(scalaVersion: String): Dependency =
    Dependency.of(
      "org.scalameta",
      s"semanticdb-scalac_$scalaVersion",
      BuildInfo.scalametaVersion,
    )

  def downloadDependency(
      dep: Dependency,
      scalaVersion: Option[String],
      classfiers: Seq[String] = Seq.empty,
      resolution: Option[ResolutionParams] = None,
  ): List[Path] = {
    fetchSettings(dep, scalaVersion, resolution)
      .addClassifiers(classfiers: _*)
      .fetch()
      .asScala
      .toList
      .map(_.toPath())
  }

  def downloadScalaSources(scalaVersion: String): List[Path] =
    downloadDependency(
      scalaDependency(scalaVersion),
      Some(scalaVersion),
      classfiers = Seq("sources"),
    )

  def downloadScala3Sources(scalaVersion: String): List[Path] =
    downloadDependency(
      scala3Dependency(scalaVersion),
      Some(scalaVersion),
      classfiers = Seq("sources"),
    )

  def downloadSemanticdbScalac(scalaVersion: String): List[Path] =
    downloadDependency(
      semanticdbScalacDependency(scalaVersion),
      Some(scalaVersion),
    )

  def downloadSemanticdbJavac: List[Path] = {
    downloadDependency(
      Dependency.of(
        "com.sourcegraph",
        "semanticdb-javac",
        BuildInfo.javaSemanticdbVersion,
      ),
      None,
    )
  }

  def downloadMtags(scalaVersion: String): List[Path] =
    downloadDependency(mtagsDependency(scalaVersion), Some(scalaVersion))

  def downloadMdoc(
      scalaBinaryVersion: String,
      resolutionParams: Option[ResolutionParams] = None,
  ): List[Path] =
    downloadDependency(
      mdocDependency(scalaBinaryVersion),
      scalaVersion = None,
      resolution = resolutionParams,
    )

  def rulesClasspath(dependencies: List[Dependency]): List[Path] = {
    for {
      dep <- dependencies
      path <- downloadDependency(dep, scalaVersion = None)
    } yield path
  }

  def toClassLoader(
      classpath: Classpath,
      classLoader: ClassLoader,
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
    downloadDependency(dependency, Some(scalaVersion))
  }

}
