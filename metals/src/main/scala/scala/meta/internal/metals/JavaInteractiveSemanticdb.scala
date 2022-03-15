package scala.meta.internal.metals

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.process.SystemProcess
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax

class JavaInteractiveSemanticdb(
    javac: AbsolutePath,
    jdkVersion: JdkVersion,
    pluginJars: List[Path],
    workspace: AbsolutePath,
    buildTargets: BuildTargets
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

    val jigsawOptions =
      addExportsFlags ++ patchModuleFlags(localSource, sourceRoot, source)
    val mainOptions =
      List(
        javac.toString,
        "-cp",
        (pluginJars ++ targetClasspath).mkString(File.pathSeparator),
        "-d",
        targetRoot.toString
      )
    val pluginOption =
      s"-Xplugin:semanticdb -sourceroot:${sourceRoot} -targetroot:${targetRoot}"
    val cmd =
      mainOptions ::: jigsawOptions ::: pluginOption :: localSource.toString :: Nil

    val stdout = List.newBuilder[String]
    val ps = SystemProcess.run(
      cmd,
      workDir,
      false,
      Map.empty,
      Some(outLine => stdout += outLine),
      Some(errLine => stdout += errLine)
    )

    val future = ps.complete.recover { case NonFatal(e) =>
      scribe.error(s"Running javac-semanticdb failed for $localSource", e)
      1
    }

    val exitCode = Await.result(future, 10.seconds)
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
      val log = stdout.result()
      if (exitCode != 0 || log.nonEmpty)
        scribe.warn(
          s"Running javac-semanticdb failed for ${source.toURI}. Output:\n${log.mkString("\n")}"
        )
      s.TextDocument()
    }

    val out = doc.copy(
      uri = source.toURI.toString(),
      text = text,
      md5 = MD5.compute(text)
    )

    workDir.deleteRecursively()
    out
  }

  private def patchModuleFlags(
      source: AbsolutePath,
      sourceRoot: AbsolutePath,
      originalSource: AbsolutePath
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

  private def addExportsFlags: List[String] = {
    if (jdkVersion.major >= 17) {
      val compilerPackages = List(
        "com.sun.tools.javac.api", "com.sun.tools.javac.code",
        "com.sun.tools.javac.model", "com.sun.tools.javac.tree",
        "com.sun.tools.javac.util"
      )
      compilerPackages.flatMap(pkg =>
        List(s"-J--add-exports", s"-Jjdk.compiler/$pkg=ALL-UNNAMED")
      )
    } else Nil
  }

}

object JavaInteractiveSemanticdb {

  def create(
      javaHome: AbsolutePath,
      workspace: AbsolutePath,
      buildTargets: BuildTargets
  ): Option[JavaInteractiveSemanticdb] = {

    def pathToJavac(p: AbsolutePath): AbsolutePath = {
      val binaryName = if (Properties.isWin) "javac.exe" else "javac"
      p.resolve("bin").resolve(binaryName)
    }

    val jdkHome =
      // jdk 8 might point at ${JDK_HOME}/jre
      if (!pathToJavac(javaHome).exists && javaHome.filename == "jre")
        javaHome.parent
      else
        javaHome

    val javac = pathToJavac(jdkHome)

    JdkVersion.getJavaVersionFromJavaHome(jdkHome) match {
      case Some(version) if javac.exists =>
        val pluginJars = Embedded.downloadSemanticdbJavac
        val instance = new JavaInteractiveSemanticdb(
          javac,
          version,
          pluginJars,
          workspace,
          buildTargets
        )
        Some(instance)
      case value =>
        scribe.warn(
          s"Can't instantiate JavaInteractiveSemanticdb (version: ${value}, jdkHome: ${jdkHome}, javac exists: ${javac.exists})"
        )
        None
    }
  }

}

case class JdkVersion(
    major: Int
) {

  def hasJigsaw: Boolean = major >= 9
}

object JdkVersion {

  def getJavaVersionFromJavaHome(
      javaHome: AbsolutePath
  ): Option[JdkVersion] = {

    def fromReleaseFile: Option[JdkVersion] = {
      val releaseFile = javaHome.resolve("release")
      if (releaseFile.exists) {
        val properties = ConfigFactory.parseFile(
          releaseFile.toFile,
          ConfigParseOptions.defaults().setSyntax(ConfigSyntax.PROPERTIES)
        )
        try {
          val version = properties
            .getString("JAVA_VERSION")
            .stripPrefix("\"")
            .stripSuffix("\"")
          JdkVersion.parse(version)
        } catch {
          case NonFatal(e) =>
            scribe.error("Failed to read jdk version from `release` file", e)
            None
        }
      } else None
    }

    def jdk8Fallback: Option[JdkVersion] = {
      val rtJar = javaHome.resolve("jre").resolve("lib").resolve("rt.jar")
      if (rtJar.exists) Some(JdkVersion(8))
      else None
    }

    fromReleaseFile.orElse(jdk8Fallback)
  }

  def parse(v: String): Option[JdkVersion] = {
    val numbers = v
      .split('-')
      .head
      .split('.')
      .toList
      .take(2)
      .map(s => Try(s.toInt).toOption)

    numbers match {
      case Some(1) :: Some(minor) :: _ =>
        Some(JdkVersion(minor))
      case Some(single) :: _ =>
        Some(JdkVersion(single))
      case _ => None
    }
  }
}
