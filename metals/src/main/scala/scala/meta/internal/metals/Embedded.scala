package scala.meta.internal.metals

import java.io.File
import java.net.MalformedURLException
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
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.internal.worksheets.MdocClassLoader
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath
import scala.meta.pc.PresentationCompiler

import coursier.cache.CacheDefaults
import coursier.cache.CacheUrl
import coursier.ivy
import coursierapi.Dependency
import coursierapi.Fetch
import coursierapi.IvyRepository
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
  private def credentials = CacheDefaults.credentials

  private lazy val mavenLocal = {
    val str = new File(sys.props("user.home")).toURI.toString
    val homeUri =
      if (str.endsWith("/"))
        str
      else
        str + "/"
    MavenRepository.of(homeUri + ".m2/repository")
  }

  lazy val repositories: List[Repository] =
    (Repository.defaults().asScala.toList ++
      List(
        mavenLocal,
        MavenRepository.of(
          "https://central.sonatype.com/repository/maven-snapshots/"
        ),
      ))
      .distinctBy {
        case m: MavenRepository => m.getBase()
        case i: IvyRepository => i.getPattern()
      }
      .map(setCredentials)

  private[Embedded] def scala3CompilerDependencies(version: String) = List(
    Dependency.of("org.scala-lang", "scala3-library_3", version),
    Dependency.of("org.scala-lang", "scala3-compiler_3", version),
    Dependency.of("org.scala-lang", "tasty-core_3", version),
  ).map(d => (d.getModule, d.getVersion)).toMap.asJava

  private def validateURL(url0: String) = {
    try Some(CacheUrl.url(url0))
    catch {
      case e: MalformedURLException =>
        val urlErrorMsg =
          "Error parsing URL " + url0 + Option(e.getMessage).fold("")(
            " (" + _ + ")"
          )
        if (url0.contains(File.separatorChar)) {
          val f = new File(url0)
          if (f.exists() && !f.isDirectory) {
            scribe.warn(s"$urlErrorMsg, and $url0 not a directory")
            None
          } else
            Some(f.toURI.toURL)
        } else {
          scribe.warn(urlErrorMsg)
          None
        }
    }
  }

  private def urlFromRepo(repo: Repository): Option[String] = repo match {
    case m: MavenRepository =>
      Some(m.getBase())
    case i: IvyRepository =>
      ivy.IvyRepository.parse(i.getPattern()).toOption.map(_.pattern).map {
        pat =>
          pat.chunks
            .takeWhile {
              case _: coursier.ivy.Pattern.Chunk.Const => true
              case _ => false
            }
            .map(_.string)
            .mkString
      }
    case _ =>
      None
  }

  def setCredentials(repo: Repository): Repository = try {
    val url = urlFromRepo(repo)
    val validatedUrl = url.flatMap(validateURL)

    validatedUrl
      .map { url =>
        val creds =
          credentials
            .flatMap(_.get())
            .find(_.autoMatches(url.toExternalForm(), realm0 = None))
            .flatMap { cred =>
              cred.usernameOpt.zip(cred.passwordOpt)
            }

        def newCredentials(user: String, pass: String) = {
          scribe.info(
            s"Setting credentials $user:${"*" * pass.length} for $url"
          )
          coursierapi.Credentials.of(user, pass)
        }
        (creds, repo) match {
          case (Some((user, pass)), mvn: MavenRepository) =>
            mvn.withCredentials(newCredentials(user, pass.value))
          case (Some((user, pass)), ivy: IvyRepository) =>
            ivy.withCredentials(newCredentials(user, pass.value))
          case _ => repo
        }
      }
      .getOrElse(repo)
  } catch {
    case NonFatal(e) =>
      scribe.error(s"Error setting credentials for $repo", e)
      repo
  }

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
            scalaLibraryDependency(scalaVersion),
            Dependency.of("org.scala-lang", "scala-compiler", scalaVersion),
            Dependency.of("org.scala-lang", "scala-reflect", scalaVersion),
          ).map(d => (d.getModule, d.getVersion)).toMap.asJava
        )
      else
        resolutionParams.forceVersions(
          scala3CompilerDependencies(scalaVersion)
        )
    }

    Fetch
      .create()
      .addRepositories(repositories: _*)
      .withDependencies(dep)
      .withResolutionParams(resolutionParams)
      .withMainArtifacts()
  }

  private def scalaLibraryDependency(scalaVersion: String): Dependency =
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

  private def scala3PresentationCompilerDependency(
      scalaVersion: String
  ): Dependency =
    Dependency.of(
      "org.scala-lang",
      s"scala3-presentation-compiler_3",
      scalaVersion,
    )

  private def mtagsDependency(
      scalaVersion: String,
      metalsVersion: String,
  ): Dependency =
    Dependency.of(
      "org.scalameta",
      s"mtags_$scalaVersion",
      metalsVersion,
    )

  private def mdocDependency(
      scalaBinaryVersion: String,
      scalaVersion: Option[String],
  ): Dependency = {
    Dependency.of(
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
    Dependency.of(
      "org.scalameta",
      s"semanticdb-scalac_$scalaVersion",
      BuildInfo.lastSupportedSemanticdb
        .getOrElse(scalaVersion, BuildInfo.scalametaVersion),
    )

  def downloadDependency(
      dep: Dependency,
      scalaVersion: Option[String] = None,
      classfiers: Seq[String] = Seq.empty,
      resolution: Option[ResolutionParams] = None,
  ): List[Path] = try {
    fetchSettings(dep, scalaVersion, resolution)
      .addClassifiers(classfiers: _*)
      .fetch()
      .asScala
      .toList
      .map(_.toPath())
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
        val module = dependency.getModule()
        val depString =
          s"${module.getOrganization()}:${module.getName()}:${dependency.getVersion}"
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
