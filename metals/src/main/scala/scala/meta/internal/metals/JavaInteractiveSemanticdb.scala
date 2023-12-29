package scala.meta.internal.metals

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.{util => ju}

import scala.concurrent.ExecutionContext
import scala.io.Source
import scala.util.Properties
import scala.util.Try

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.pc.JavaMetalsGlobal
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

class JavaInteractiveSemanticdb(
    jdkVersion: JdkVersion,
    pluginJars: List[Path],
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
) {
  private val readonly = workspace.resolve(Directories.readonly)

  def textDocument(source: AbsolutePath, text: String): s.TextDocument = {
    val workDir = AbsolutePath(
      Files.createTempDirectory("metals-javac-semanticdb")
    )
    val targetRoot = workDir.resolve("target")
    Files.createDirectory(targetRoot.toNIO)

    val localSource =
      if (source.isLocalFileSystem(workspace)) {
        source
      } else {
        val sourceRoot = workDir.resolve("source")
        Files.createDirectory(sourceRoot.toNIO)
        val localSource = sourceRoot.resolve(source.filename)
        Files.write(localSource.toNIO, text.getBytes)
        localSource
      }

    val sourceRoot = localSource.parent
    val targetClasspath = buildTargets
      .inferBuildTarget(source)
      .flatMap(buildTargets.targetJarClasspath)
      .getOrElse(Nil)
      .map(_.toString)

    val jigsawOptions = patchModuleFlags(localSource, sourceRoot, source)
    val mainOptions =
      List(
        "-cp",
        (pluginJars ++ targetClasspath).mkString(File.pathSeparator),
        "-d",
        targetRoot.toString,
      )
    val pluginOption =
      s"-Xplugin:semanticdb -sourceroot:${sourceRoot} -targetroot:${targetRoot}"
    val allOptions = mainOptions ::: jigsawOptions ::: pluginOption :: Nil

    val writer = new StringWriter()
    val printWriter = new PrintWriter(writer)
    try {
      // JavacFileManager#getLocationForModule specifically tests that JavaFileObject is instanceof PathFileObject when using Patch-Module
      // so can't use Metals SourceJavaFileObject
      val javaFileObject = JavaMetalsGlobal.makeFileObject(localSource.toFile)

      val javacTask = JavaMetalsGlobal.classpathCompilationTask(
        javaFileObject,
        Some(printWriter),
        allOptions,
      )

      javacTask.call()
    } catch {
      case e: Throwable =>
        scribe.error(
          s"Can't run javac on $localSource with options: [${allOptions.mkString("\n")}]",
          e,
        )
    }

    val semanticdbFile =
      targetRoot
        .resolve("META-INF")
        .resolve("semanticdb")
        .resolve(s"${localSource.filename}.semanticdb")

    val doc = if (semanticdbFile.exists) {
      FileIO
        .readAllDocuments(semanticdbFile)
        .headOption
        .getOrElse(s.TextDocument())
    } else {
      val log = writer.getBuffer()
      scribe.warn(
        s"Running javac-semanticdb failed for ${source.toURI}. Output:\n${log}"
      )
      s.TextDocument()
    }

    val documentSource = scala.util
      .Try(workspace.toNIO.relativize(source.toNIO))
      .toOption
      .map { relativeUri =>
        val relativeString =
          if (Properties.isWin) relativeUri.toString().replace("\\", "/")
          else relativeUri.toString()
        relativeString
      }
      .getOrElse(source.toString())

    val out = doc.copy(
      uri = documentSource,
      text = text,
      md5 = MD5.compute(text),
    )

    workDir.deleteRecursively()
    out
  }

  private def patchModuleFlags(
      source: AbsolutePath,
      sourceRoot: AbsolutePath,
      originalSource: AbsolutePath,
  ): List[String] = {
    // Jigsaw doesn't allow compiling source with package
    // that is declared in some existing module.
    // It fails with: `error: package exists in another module: $packageName`
    // but it might be fixed by passing `--patch-module $moduleName=$sourceRoot` option.
    //
    // Currently there is no infrastucture to detect if package belong to jigsaw module or not
    // so this case is covered only for JDK sources.
    if (jdkVersion.hasJigsaw) {
      source.toRelativeInside(readonly) match {
        case Some(rel) =>
          val names = rel.toNIO.iterator().asScala.toList.map(_.filename)
          names match {
            case Directories.dependenciesName :: JdkSources.zipFileName :: moduleName :: _ =>
              List("--patch-module", s"$moduleName=$sourceRoot")
            case _ =>
              Nil
          }
        case None =>
          if (
            originalSource.jarPath.exists(_.filename == JdkSources.zipFileName)
          ) {
            originalSource.toNIO
              .iterator()
              .asScala
              .headOption
              .map(_.filename)
              .map(moduleName =>
                List("--patch-module", s"$moduleName=$sourceRoot")
              )
              .getOrElse(Nil)
          } else {
            Nil
          }
      }
    } else Nil
  }
}

object JavaInteractiveSemanticdb {

  def create(
      workspace: AbsolutePath,
      buildTargets: BuildTargets,
      jdkVersion: JdkVersion,
  ): JavaInteractiveSemanticdb = {

    val pluginJars = Embedded.downloadSemanticdbJavac
    new JavaInteractiveSemanticdb(
      jdkVersion,
      pluginJars,
      workspace,
      buildTargets,
    )
  }
}

case class JdkVersion(
    major: Int
) {

  def hasJigsaw: Boolean = major >= 9
}

object JdkVersion {

  def maybeJdkVersionFromJavaHome(
      maybeJavaHome: Option[AbsolutePath]
  )(implicit ec: ExecutionContext): Option[JdkVersion] = {
    maybeJavaHome.flatMap { javaHome =>
      fromReleaseFile(javaHome).orElse {
        fromShell(javaHome)
      }
    }
  }

  def fromShell(
      javaHome: AbsolutePath
  )(implicit ec: ExecutionContext): Option[JdkVersion] = {
    ShellRunner
      .runSync(
        List(javaHome.resolve("bin/java").toString, "-version"),
        javaHome,
        redirectErrorOutput = true,
        maybeJavaHome = Some(javaHome.toString()),
      )
      .flatMap { javaVersionResponse =>
        "\\d+\\.\\d+\\.\\d+".r
          .findFirstIn(javaVersionResponse)
          .flatMap(JdkVersion.parse)
      }
  }

  def fromReleaseFile(javaHome: AbsolutePath): Option[JdkVersion] =
    fromReleaseFileString(javaHome).flatMap(f => parse(f))

  def fromReleaseFileString(javaHome: AbsolutePath): Option[String] =
    Seq(javaHome.resolve("release"), javaHome.parent.resolve("release"))
      .filter(_.exists)
      .flatMap { releaseFile =>
        val props = new ju.Properties
        props.load(Source.fromFile(releaseFile.toFile).bufferedReader())
        props.asScala
          .get("JAVA_VERSION")
          .map(_.stripPrefix("\"").stripSuffix("\""))
      }
      .headOption

  def parse(v: String): Option[JdkVersion] = {
    val numbers = Try {
      v
        .split('-')
        .head
        .split('.')
        .toList
        .take(2)
        .flatMap(s => Try(s.toInt).toOption)
    }.toOption

    numbers match {
      case Some(1 :: minor :: _) =>
        Some(JdkVersion(minor))
      case Some(single :: _) =>
        Some(JdkVersion(single))
      case _ => None
    }
  }
}
