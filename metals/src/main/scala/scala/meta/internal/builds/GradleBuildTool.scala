package scala.meta.internal.builds
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Properties

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import coursierapi.Credentials
import coursierapi.IvyRepository
import coursierapi.MavenRepository
import coursierapi.Repository

case class GradleBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BloopInstallProvider {

  private val initScriptName = "init-script.gradle"
  private val gradleBloopVersion = BuildInfo.gradleBloopVersion
  private def initScript = {
    s"""
       |initscript {
       |${GradleBuildTool.toGradleRepositories(Repository.defaults.asScala.toList)}
       |  dependencies {
       |    classpath 'ch.epfl.scala:gradle-bloop_2.12:$gradleBloopVersion'
       |  }
       |}
       |allprojects {
       |  apply plugin: bloop.integrations.gradle.BloopPlugin
       |}
    """.stripMargin.getBytes()
  }

  private lazy val initScriptPath: Path = {
    Files.write(tempDir.resolve(initScriptName), initScript)
  }

  private lazy val gradleWrapper = {
    if (Properties.isWin) "gradlew.bat"
    else "gradlew"
  }

  private lazy val embeddedGradleLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, gradleWrapper)
    out.toFile.setExecutable(true)
    Set(s"gradle-wrapper.jar", "gradle-wrapper.properties").foreach {
      fileName =>
        BuildTool.copyFromResource(tempDir, s"gradle/wrapper/$fileName")
    }
    AbsolutePath(out)
  }

  private def isBloopConfigured(): Boolean = {
    val gradlePropsFile = projectRoot.resolve("gradle.properties")
    try {
      val contents =
        new String(gradlePropsFile.readAllBytes, StandardCharsets.UTF_8)
      contents.linesIterator.exists(_.startsWith("bloop.configured=true"))
    } catch {
      case _: IOException => false
    }
  }

  private def workspaceGradleLauncher(workspace: AbsolutePath): AbsolutePath = {
    workspace.resolve(gradleWrapper)
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    GradleDigest.current(projectRoot)

  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val cmd = {
      if (isBloopConfigured())
        List("--stacktrace", "--console=plain", "bloopInstall")
      else {
        List(
          "--stacktrace",
          "--console=plain",
          "--init-script",
          initScriptPath.toString,
          "bloopInstall",
        )
      }
    }

    userConfig().gradleScript match {
      case Some(script) =>
        script :: cmd
      case None =>
        val workspaceGradle = workspaceGradleLauncher(workspace)
        if (workspaceGradle.isFile)
          workspaceGradle.toString() :: cmd
        else
          embeddedGradleLauncher.toString() :: cmd
    }
  }

  // @tgodzik This is the wrapper version we specify it as such,
  // since it's hard to determine which version will be used as gradle
  // doesn't save it in any settings
  override def version: String = "7.5.0"

  override def minimumVersion: String = "5.0.0"

  override def recommendedVersion: String = version

  override def toString: String = "Gradle"

  def executableName = GradleBuildTool.name
}

object GradleBuildTool {
  def name = "gradle"

  def isGradleRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean = {
    val buildSrc = workspace.toNIO.resolve("buildSrc")
    val filename = path.toNIO.getFileName.toString
    path.toNIO.startsWith(buildSrc) ||
    filename.endsWith(".gradle") ||
    filename.endsWith(".gradle.kts")
  }

  def toGradleRepositories(
      repos: List[Repository]
  ): String = {
    def authString(cr: Credentials) =
      if (cr == null) ""
      else
        s"""|
            |      credentials {
            |        username "${cr.getUser()}"
            |        password "${cr.getPassword()}"
            |      }""".stripMargin

    repos.collect {
      case mr: MavenRepository if mr != Repository.central =>
        // filter central etc.
        s"""|    maven {
            |      url "${mr.getBase()}"${authString(mr.getCredentials())}
            |    }""".stripMargin

      case ir: IvyRepository =>
        ir.getPattern().split("\\/\\[").toList match {
          case url :: rest => {
            val layout = "[" ++ rest.mkString("/[")
            s"""|    ivy {
                |      url "${url}"
                |      patternLayout {
                |        artifact "${layout}"
                |      }${authString(ir.getCredentials())}
                |    }""".stripMargin
          }
          case Nil => ""
        }
      case mr if mr == Repository.central => "    mavenCentral()"
    } match {
      case Nil =>
        """|  repositories {
           |    mavenCentral()
           |  }
           |""".stripMargin
      case repos =>
        s"""|  repositories {
            |${repos.mkString("\n")}
            |  }
            |""".stripMargin
    }
  }
}
