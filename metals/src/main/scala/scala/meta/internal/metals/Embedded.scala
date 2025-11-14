package scala.meta.internal.metals

import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ServiceLoader

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.semver.SemVer.Version
import scala.meta.internal.worksheets.MdocClassLoader
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.pc.PresentationCompiler

import coursier.Classifier
import coursier.Dependency
import coursier.Fetch
import coursier.MavenRepository
import coursier.ModuleName
import coursier.Organization
import coursier.Repository
import coursier.Resolve
import coursier.VersionConstraint
import coursier.cache.FileCache
import coursier.core.Authentication
import coursier.ivy.IvyRepository
import coursier.params.ResolutionParams
import coursier.util.Task
import mdoc.interfaces.Mdoc
import scalafix.interfaces.Scalafix
import scalafix.interfaces.ScalafixException

/**
 * Wrapper around software that is embedded with Metals.
 *
 * - sbt-launch.jar
 * - mdoc
 */
final class Embedded(
    workDoneProgress: WorkDoneProgress
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
      workDoneProgress.trackBlocking("Preparing worksheets") {
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
      mtags: MtagsBinaries.Artifacts
  ): PresentationCompiler = {
    val classloader = presentationCompilers.getOrElseUpdate(
      ScalaVersions.dropVendorSuffix(mtags.scalaVersion),
      newPresentationCompilerClassLoader(mtags),
    )

    val presentationCompilerClassname =
      if (mtags.isScala3PresentationCompiler) {
        "dotty.tools.pc.ScalaPresentationCompiler"
      } else {
        classOf[ScalaPresentationCompiler].getName()
      }

    serviceLoader(
      classOf[PresentationCompiler],
      presentationCompilerClassname,
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
    /* note(@tgodzik) we add an exclusion so that the mdoc classlaoder does not try to
     * load coursierapi.Logger and instead will use the already loaded one
     */
    val resolutionParams = ResolutionParams().withExclusions(
      Set((Organization("io.get-coursier"), ModuleName("interface")))
    )

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
        resolutionParams.withForceVersion0(
          Embedded.scala3CompilerDependencies(version)
        )
    }
    val jars =
      Embedded.downloadMdoc(
        scalaBinaryVersion,
        scalaVersion,
        Some(fullResolution),
      )

    val parent = new MdocClassLoader(this.getClass.getClassLoader)

    // Full mdoc classpath seems to be causing some issue with akka
    // We want to keep a minimal set of jars needed here
    val runtimeClasspath = jars.filter { path =>
      val pathString = path.toString
      pathString.contains("using_directives") ||
      pathString.contains("scala-lang") ||
      pathString.contains("fansi") ||
      pathString.contains("pprint") ||
      pathString.contains("sourcecode") ||
      pathString.contains("mdoc") ||
      pathString.contains("scalameta") ||
      pathString.contains("metaconfig") ||
      pathString.contains("diffutils") ||
      pathString.contains("scala-sbt")
    }
    val urls = runtimeClasspath.iterator.map(_.toUri().toURL()).toArray
    new URLClassLoader(urls, parent)
  }

  private def newPresentationCompilerClassLoader(
      mtags: MtagsBinaries.Artifacts
  ): URLClassLoader = {
    val allJars = mtags.jars.iterator
    val allURLs = allJars.map(_.toUri.toURL).toArray
    // Share classloader for a subset of types.
    val parent =
      new PresentationCompilerClassLoader(this.getClass.getClassLoader)
    new URLClassLoader(allURLs, parent)
  }

}

object Embedded {
  private val jdkVersion = JdkVersion.parse(Properties.javaVersion)

  private lazy val mavenLocal = {
    val str = new File(sys.props("user.home")).toURI.toString
    val homeUri =
      if (str.endsWith("/"))
        str
      else
        str + "/"
    MavenRepository(homeUri + ".m2/repository")
  }

  lazy val repositories: List[Repository] =
    (Resolve.defaultRepositories ++
      List(
        mavenLocal,
        MavenRepository(
          "https://central.sonatype.com/repository/maven-snapshots/"
        ),
      )).distinctBy {
      case m: MavenRepository => m.root
      case i: IvyRepository => i.pattern
    }.toList

  lazy val apiRepositories: List[coursierapi.Repository] =
    repositories.collect {
      case mvn: MavenRepository =>
        val credentialsOpt = mvn.authentication.map(credentials)
        coursierapi.MavenRepository
          .of(mvn.root)
          .withCredentials(credentialsOpt.orNull)
      case ivy: IvyRepository =>
        val credentialsOpt = ivy.authentication.map(credentials)
        val mdPatternOpt = ivy.metadataPatternOpt.map(_.string)
        coursierapi.IvyRepository
          .of(ivy.pattern.string)
          .withMetadataPattern(mdPatternOpt.orNull)
          .withCredentials(credentialsOpt.orNull)
    }

  private def credentials(auth: Authentication): coursierapi.Credentials =
    coursierapi.Credentials.of(auth.user, auth.passwordOpt.getOrElse(""))

  private[Embedded] def scala3CompilerDependencies(version: String) = List(
    dependencyOf(
      "org.scala-lang",
      "scala3-library_3",
      version,
    ),
    dependencyOf(
      "org.scala-lang",
      "scala3-compiler_3",
      version,
    ),
    dependencyOf(
      "org.scala-lang",
      "tasty-core_3",
      version,
    ),
  ).map(d => (d.module, d.versionConstraint)).toMap

  def fetchSettings(
      dep: Dependency,
      scalaVersion: Option[String],
      resolution: Option[ResolutionParams] = None,
  ): Fetch[Task] = {

    val mtagsInterfaceOverride = Map(
      "3.5.0" -> "1.3.1",
      "3.4.3" -> "1.3.0",
      "3.4.2" -> "1.3.0",
      "3.4.1" -> "1.2.1",
      "3.4.0" -> "1.2.0",
    )

    val resolutionParams = resolution.getOrElse(ResolutionParams())

    val resolutionWithOverride = scalaVersion
      .flatMap { scalaVersion =>
        val resolution =
          if (!ScalaVersions.isScala3Version(scalaVersion))
            resolutionParams.withForceVersion0(
              List(
                scalaLibraryDependency(scalaVersion),
                dependencyOf(
                  "org.scala-lang",
                  "scala-compiler",
                  scalaVersion,
                ),
                dependencyOf(
                  "org.scala-lang",
                  "scala-reflect",
                  scalaVersion,
                ),
              ).map(d => (d.module, d.versionConstraint)).toMap
            )
          else
            resolutionParams.withForceVersion0(
              scala3CompilerDependencies(scalaVersion)
            )
        mtagsInterfaceOverride.get(scalaVersion).map { overrideVersion =>
          resolution.withForceVersion0(
            Map(
              coursier.Module(
                Organization("org.scalameta"),
                ModuleName("mtags-interfaces"),
              ) -> VersionConstraint(overrideVersion)
            )
          )
        }
      }
      .getOrElse(resolutionParams)
    val fileCache = FileCache()
    Fetch(fileCache)
      .addRepositories(repositories: _*)
      .withDependencies(Seq(dep))
      .withResolutionParams(resolutionWithOverride)
      .withMainArtifacts()
  }

  private def scalaLibraryDependency(scalaVersion: String): Dependency =
    dependencyOf("org.scala-lang", "scala-library", scalaVersion)

  private def scala3Dependency(scalaVersion: String): Dependency = {
    val binaryVersion =
      ScalaVersions.scalaBinaryVersionFromFullVersion(scalaVersion)
    if (binaryVersion.startsWith("3")) {
      dependencyOf(
        "org.scala-lang",
        s"scala3-library_$binaryVersion",
        scalaVersion,
      )
    } else {
      dependencyOf(
        "ch.epfl.lamp",
        s"dotty-library_$binaryVersion",
        scalaVersion,
      )
    }
  }

  def dependencyOf(
      organization: String,
      module: String,
      version: String,
  ): Dependency =
    Dependency(
      coursier.Module(Organization(organization), ModuleName(module)),
      version,
    )

  private def scala3PresentationCompilerDependency(
      scalaVersion: String
  ): Dependency =
    dependencyOf(
      "org.scala-lang",
      s"scala3-presentation-compiler_3",
      scalaVersion,
    )

  private def mtagsDependency(
      scalaVersion: String,
      metalsVersion: String,
  ): Dependency =
    dependencyOf(
      "org.scalameta",
      s"mtags_$scalaVersion",
      metalsVersion,
    )

  private def mdocDependency(
      scalaBinaryVersion: String,
      scalaVersion: Option[String],
  ): Dependency = {
    dependencyOf(
      "org.scalameta",
      s"mdoc_${scalaBinaryVersion}",
      if (scalaBinaryVersion == "2.11") "2.2.24"
      // from 2.2.24 mdoc is compiled with 3.1.x which is incompatible with 3.0.x
      else if (scalaVersion.exists(_.startsWith("3.0"))) "2.2.23"
      // from 2.4.0 mdoc is released with Scala LTS 3.3.x
      else if (
        scalaVersion.exists(_.startsWith("3.1")) ||
        scalaVersion.exists(_.startsWith("3.2"))
      ) "2.3.8"
      // from 2.5.0 mdoc is released with JDK 11
      else if (jdkVersion.exists(_.major < 11)) "2.4.0"
      else BuildInfo.mdocVersion,
    )
  }

  private def semanticdbScalacDependency(scalaVersion: String): Dependency =
    dependencyOf(
      "org.scalameta",
      s"semanticdb-scalac_$scalaVersion",
      BuildInfo.lastSupportedSemanticdb
        .getOrElse(scalaVersion, BuildInfo.scalametaVersion),
    )

  def downloadDependency(
      organization: String,
      module: String,
      version: String,
  ): List[Path] = {
    downloadDependency(dependencyOf(organization, module, version))
  }

  def downloadDependency(
      dep: Dependency,
      scalaVersion: Option[String] = None,
      classfiers: Seq[String] = Seq.empty,
      resolution: Option[ResolutionParams] = None,
  ): List[Path] = try {
    val settings = fetchSettings(dep, scalaVersion, resolution)
      .addClassifiers(classfiers.map(Classifier(_)): _*)
    val withPossibleSnapshotRepo =
      // Scala 3.4.x series depends on mtags snapshot versions
      if (scalaVersion.exists(_.startsWith("3.4"))) {
        settings
          .addRepositories(
            MavenRepository(
              "https://oss.sonatype.org/content/repositories/snapshots/"
            )
          )
      } else settings

    withPossibleSnapshotRepo
      .run()
      .map(_.toPath())
      .toList

  } catch {
    case NonFatal(e) =>
      scribe.error(s"Error downloading $dep", e)
      val result = fallbackDownload(dep)
      if (result.isEmpty) {
        throw e
      } else {
        result
      }

  }

  def downloadScalaSources(scalaVersion: String): List[Path] =
    downloadDependency(
      scalaLibraryDependency(scalaVersion),
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

  def downloadScalafix(scalaVersion: String): List[Path] =
    downloadDependency(
      scalafixDependency(scalaVersion),
      Some(scalaVersion),
    )

  /**
   *  Fallback to use the scalafix version that is compatible with the requested scala version,
   *  Follows the logic from scalafix.
   */
  private def scalaVersionForScalafixCli(
      requestedScalaVersion: String
  ): (String, String) = {

    val properties = new java.util.Properties()
    val propertiesPath = "scalafix-interfaces.properties"
    val stream =
      classOf[Scalafix].getClassLoader().getResourceAsStream(propertiesPath);
    try {
      properties.load(stream)
    } catch {
      case e @ (_: IOException | _: NullPointerException) =>
        throw new ScalafixException(
          "Failed to load '" + propertiesPath + "' to lookup versions",
          e,
        )
    }

    val scalafixVersion = properties.getProperty("scalafixVersion");
    val scalaVersion = Version.fromString(requestedScalaVersion) match {
      case Version(2, minor, _, _, _, _) =>
        properties.getProperty(s"scala2$minor")
      case Version(3, minor, _, _, _, _) if minor <= 3 =>
        properties.getProperty(s"scala33")
      case Version(3, minor, _, _, _, _) =>
        Option(properties.getProperty(s"scala3$minor"))
          .getOrElse(properties.getProperty("scala3Next"))
      case _ =>
        throw new IllegalArgumentException(
          "Unsupported scala version " + requestedScalaVersion
        )
    }
    (scalafixVersion, scalaVersion)
  }

  private def scalafixDependency(scalaVersion: String): Dependency = {
    val (scalafixVersion, scalaVersionForScalafix) = scalaVersionForScalafixCli(
      scalaVersion
    )
    dependencyOf(
      "ch.epfl.scala",
      s"scalafix-cli_$scalaVersionForScalafix",
      scalafixVersion,
    )
  }

  def downloadSemanticdbJavac: List[Path] = {
    downloadDependency(
      dependencyOf(
        "com.sourcegraph",
        "semanticdb-javac",
        BuildInfo.javaSemanticdbVersion,
      ),
      None,
    )
  }

  def downloadMtags(scalaVersion: String, metalsVersion: String): List[Path] =
    downloadDependency(
      mtagsDependency(scalaVersion, metalsVersion),
      Some(scalaVersion),
    )

  def downloadScala3PresentationCompiler(scalaVersion: String): List[Path] =
    downloadDependency(
      scala3PresentationCompilerDependency(scalaVersion),
      Some(scalaVersion),
    )

  def downloadMdoc(
      scalaBinaryVersion: String,
      scalaVersion: Option[String],
      resolutionParams: Option[ResolutionParams] = None,
  ): List[Path] =
    downloadDependency(
      mdocDependency(scalaBinaryVersion, scalaVersion),
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
        scalaLibraryDependency(scalaVersion)
    downloadDependency(dependency, Some(scalaVersion))
  }

  private val userHome = Paths.get(System.getProperty("user.home"))

  /**
   * There are some cases where Metals wouldn't be able to download
   * dependencies using coursier, in those cases we can try to use a locally
   * installed coursier to fetch the dependency.
   *
   * One potential issue is when we have credential issues
   */
  def fallbackDownload(
      dependency: Dependency
  ): List[Path] = {
    // This is a fallback so it should be fine to use the global execution context
    implicit val ec: ExecutionContextExecutor = ExecutionContext.global

    /* Metals VS Code extension will download coursier for us most of the times */
    def inVsCodeMetals = {
      val cs = userHome.resolve(".metals/cs")
      val csExe = userHome.resolve(".metals/cs.exe")
      if (Files.exists(cs)) Some(cs)
      else if (Files.exists(csExe)) Some(csExe)
      else None
    }
    findInPath("cs")
      .orElse(findInPath("coursier"))
      .orElse(inVsCodeMetals) match {
      case None => Nil
      case Some(path) =>
        scribe.info(
          s"Found coursier in path under $path, using it to fetch dependency"
        )
        val module = dependency.module
        val depString =
          s"${module.organization.value}:${module.name.value}:${dependency.versionConstraint.asString}"
        ShellRunner.runSync(
          List(path.toString(), "fetch", depString),
          AbsolutePath(userHome),
          redirectErrorOutput = false,
        ) match {
          case Some(out) =>
            val lines = out.linesIterator.toList
            val jars = lines.map(Paths.get(_))
            jars
          case None => Nil
        }
    }
  }

  def findInPath(app: String): Option[Path] = {

    def endsWithCaseInsensitive(s: String, suffix: String): Boolean =
      s.length >= suffix.length &&
        s.regionMatches(
          true,
          s.length - suffix.length,
          suffix,
          0,
          suffix.length,
        )

    val asIs = Paths.get(app)
    if (Paths.get(app).getNameCount >= 2) Some(asIs)
    else {
      def pathEntries =
        Option(System.getenv("PATH")).iterator
          .flatMap(_.split(File.pathSeparator).iterator)
      def pathExts =
        if (Properties.isWin)
          Option(System.getenv("PATHEXT")).iterator
            .flatMap(_.split(File.pathSeparator).iterator)
        else Iterator("")
      def matches = for {
        dir <- pathEntries
        ext <- pathExts
        app0 = if (endsWithCaseInsensitive(app, ext)) app else app + ext
        path = Paths.get(dir).resolve(app0)
        if Files.isExecutable(path) && !Files.isDirectory(path)
      } yield path
      matches.toStream.headOption
    }
  }

}
