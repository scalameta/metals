package metals

import java.nio.file.Paths
import java.{util => ju}

import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Properties
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.sbtmetals.BuildInfo

import sbt.Keys
import sbt.Keys._
import sbt._
import sbt.internal.inc.ScalaInstance
import sbt.plugins.SemanticdbPlugin

object MetalsPlugin extends AutoPlugin {
  override def requires = SemanticdbPlugin
  override def trigger = allRequirements

  object autoImport {
    lazy val javaSemanticdbEnabled =
      settingKey[Boolean]("Enables SemanticDB Javac plugin")
  }
  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] = Def.settings(
    Keys.semanticdbVersion := {
      if (requiresSemanticdb.value && !isScala3.value)
        BuildInfo.semanticdbVersion
      else Keys.semanticdbVersion.value
    },
    semanticdbEnabled := {
      semanticdbEnabled.value || requiresSemanticdb.value
    },
    semanticdbOptions ++= {
      if (isScala3.value || !requiresSemanticdb.value) Seq()
      else
        Seq(
          // Needed for "find references" on implicits and `apply` methods.
          "-P:semanticdb:synthetics:on",
          // Don't fail compilation in case of Scalameta crash during SemanticDB generation.
          "-P:semanticdb:failures:warning",
          s"-P:semanticdb:sourceroot:${(ThisBuild / baseDirectory).value}",
        )
    },
    javaSemanticdbEnabled := bspEnabled.value,
    javaHome := {
      javaHome.value match {
        case None =>
          // In case if jdk is >= 17 we need to set `javaHome` explicitly
          // to force sbt creating ForkJavaCompiler.
          // Otherwise it creates LocalJavaCompiler that ignores `-J` flags
          // and semanticdb plugin fails compilation
          // See sbt.internal.inc.javac.JavaCompiler.directOrFork
          // https://github.com/sbt/zinc/blob/dd1f3596494b4c4d7a19256bc921e765d3dc12c8/internal/zinc-compile-core/src/main/scala/sbt/internal/inc/javac/JavaCompiler.scala#L41-L67
          JdkVersion.getJavaVersionFromJavaHome(processJavaHome) match {
            case Some(v) if v.major >= 17 => Some(processJavaHome)
            case _ => None
          }
        case defined @ Some(_) => defined
      }
    },
    javacOptions ++= {
      if (javaSemanticdbEnabled.value)
        javaSemanticdbOptions.value
      else
        Nil
    },
    allDependencies ++= {
      if (javaSemanticdbEnabled.value) {
        val existing = ivyConfigurations.value.map(_.name).toSet
        val configurations = Seq(
          Configurations.CompileInternal,
          Configurations.TestInternal,
          Configurations.IntegrationTestInternal,
        ).map(_.name).filter(existing.contains).mkString(",")
        List(
          "com.sourcegraph" % "semanticdb-javac" % BuildInfo.javaSemanticdbVersion % configurations
        )
      } else
        Nil
    },
  )

  private def processJavaHome: File = file(System.getProperty("java.home"))

  def requiresSemanticdb: Def.Initialize[Boolean] = Def.setting {
    bspEnabled.value
  }

  def isScala3: Def.Initialize[Boolean] = Def.setting {
    ScalaInstance.isDotty(scalaVersion.value)
  }

  def javaSemanticdbOptions: Def.Initialize[Seq[String]] = {
    def exportsFlags(version: JdkVersion): List[String] = {
      if (version.major >= 17) {
        val compilerPackages = List(
          "com.sun.tools.javac.api", "com.sun.tools.javac.code",
          "com.sun.tools.javac.model", "com.sun.tools.javac.tree",
          "com.sun.tools.javac.util",
        )
        compilerPackages.flatMap(pkg =>
          List(s"-J--add-exports", s"-Jjdk.compiler/$pkg=ALL-UNNAMED")
        )
      } else Nil
    }
    Def.setting {
      val explicit = javaHome.value
      JdkVersion.inferJavaVersion(explicit) match {
        case None => Seq.empty
        case Some(version) =>
          val sourceRoot = (ThisBuild / baseDirectory).value
          val targetRoot = (Compile / semanticdbTargetRoot).value
          val pluginOption =
            s"-Xplugin:semanticdb -sourceroot:${sourceRoot} -targetroot:${targetRoot} -build-tool:sbt"
          pluginOption :: exportsFlags(version)
      }
    }
  }

  case class JdkVersion(major: Int) {

    def hasJigsaw: Boolean = major >= 9
  }

  object JdkVersion {

    def inferJavaVersion(explicitJavaHome: Option[File]): Option[JdkVersion] =
      getJavaVersionFromJavaHome(
        explicitJavaHome.getOrElse(processJavaHome)
      )

    def getJavaVersionFromJavaHome(
        javaHome: File
    ): Option[JdkVersion] = {

      def fromReleaseFile: Option[JdkVersion] = {
        val releaseFile = javaHome / "release"
        if (releaseFile.exists) {
          val props = new ju.Properties
          props.load(Source.fromFile(releaseFile).bufferedReader())
          try {
            props.asScala
              .get("JAVA_VERSION")
              .map(_.stripPrefix("\"").stripSuffix("\""))
              .flatMap(JdkVersion.parse)
          } catch {
            case NonFatal(e) =>
              None
          }
        } else None
      }

      def jdk8Fallback: Option[JdkVersion] = {
        val rtJar = javaHome / "jre" / "lib" / "rt.jar"
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

}
