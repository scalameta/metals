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
    jdkVersion: JavaInteractiveSemanticdb.JdkVersion,
    pluginJars: List[Path],
    workspace: AbsolutePath,
    buildTargets: BuildTargets
) {

  private val readonly = workspace.resolve(Directories.readonly)

  def textDocument(source: AbsolutePath, text: String): s.TextDocument = {
    val sourceRoot = source.parent
    val workDir = AbsolutePath(
      Files.createTempDirectory("metals-javac-semanticdb")
    )
    val targetRoot = workDir.resolve("target")
    Files.createDirectory(targetRoot.toNIO)

    val targetClasspath = buildTargets
      .inferBuildTarget(sourceRoot)
      .flatMap(buildTargets.targetJarClasspath)
      .getOrElse(Nil)
      .map(_.toString)

    val jigsawOptions = addExportsFlags ++ patchModuleFlags(source, sourceRoot)
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
      mainOptions ::: jigsawOptions ::: pluginOption :: source.toString :: Nil

    val stdout = List.newBuilder[String]
    val ps = SystemProcess.run(
      cmd,
      workDir,
      false,
      Map.empty,
      outLine => stdout += outLine,
      errLine => stdout += errLine
    )

    val future = ps.complete.recover { case NonFatal(e) =>
      scribe.error(s"Running javac-semanticdb failed for $source", e)
      1
    }

    val exitCode = Await.result(future, 10.seconds)
    val semanticdbFile =
      targetRoot
        .resolve("META-INF")
        .resolve("semanticdb")
        .resolve(s"${source.filename}.semanticdb")

    val doc = if (semanticdbFile.exists) {
      FileIO
        .readAllDocuments(semanticdbFile)
        .headOption
        .getOrElse(s.TextDocument())
    } else {
      val log = stdout.result()
      if (exitCode != 0 || log.nonEmpty)
        scribe.warn(
          s"Running javac-semanticdb failed for $source. Output:\n${log.mkString("\n")}"
        )
      s.TextDocument()
    }

    val out = doc.copy(
      uri = source.toURI.toString(),
      text = text,
      md5 = MD5.compute(source, text)
    )

    workDir.deleteRecursively()
    out
  }

  private def patchModuleFlags(
      source: AbsolutePath,
      sourceRoot: AbsolutePath
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
        case None => Nil
      }
    } else Nil
  }

  private def addExportsFlags: List[String] = {
    if (jdkVersion.major >= 17) {
      val compilerPackages = List(
        "com.sun.tools.javac.api",
        "com.sun.tools.javac.code",
        "com.sun.tools.javac.model",
        "com.sun.tools.javac.tree",
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

    getJavaVersionFromJavaHome(jdkHome) match {
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

  private def getJavaVersionFromJavaHome(
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
          scribe.info(s"Parse release: ${version}")
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
      if (rtJar.exists) Some(JdkVersion(1, 8))
      else None
    }

    fromReleaseFile.orElse(jdk8Fallback)
  }

  case class JdkVersion(
      major: Int,
      minor: Int
  ) {

    def hasJigsaw: Boolean = major >= 11 || (minor == 9 && major == 1)
  }

  object JdkVersion {

    def parse(v: String): Option[JdkVersion] = {
      val numbers = v.split('.').toList.take(3).map(s => Try(s.toInt).toOption)
      numbers match {
        case Some(single) :: Nil =>
          if (single > 10) Some(JdkVersion(single, 0))
          else Some(JdkVersion(1, single))
        case Some(major) :: Some(minor) :: Some(_) :: Nil =>
          Some(JdkVersion(major, minor))
        case _ => None
      }
    }
  }
}
