package scala.meta.internal.builds
import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import scala.util.Properties
import scala.meta.internal.metals.MetalsServerConfig

case class GradleBuildTool() extends BuildTool {

  private val initScriptName = "init-script.gradle"

  private val versionsArray =
    BuildInfo.supportedScalaVersions
      .map(s => scala.meta.Lit.String(s).syntax)
      .mkString("[", ",", "]")

  private def additionalRepos = {
    val isSnapshotVersion = BuildInfo.gradleBloopVersion.contains("+")
    if (isSnapshotVersion)
      """|maven{
         |  url 'https://dl.bintray.com/scalacenter/releases'
         |}""".stripMargin
    else {
      ""
    }
  }

  private val initScript =
    s"""
       |initscript {
       |  repositories{
       |    $additionalRepos
       |    mavenCentral()
       |  }
       |  dependencies {
       |    classpath 'ch.epfl.scala:gradle-bloop_2.11:${BuildInfo.gradleBloopVersion}'
       |  }
       |}
       |allprojects {
       |  apply plugin: bloop.integrations.gradle.BloopPlugin
       |}
    """.stripMargin.getBytes()

  private lazy val initScriptPath: Path = {
    Files.write(tempDir.resolve(initScriptName), initScript)
  }

  private lazy val embeddedGradleLauncher: AbsolutePath = {
    val gradleWrapper =
      if (Properties.isWin) "gradlew.bat"
      else "gradlew"
    val out = BuildTool.copyFromResource(tempDir, gradleWrapper)
    out.toFile.setExecutable(true)
    Set(s"gradle-wrapper.jar", "gradle-wrapper.properties").foreach {
      fileName =>
        BuildTool.copyFromResource(tempDir, s"gradle/wrapper/$fileName")
    }
    AbsolutePath(out)
  }

  override def digest(
      workspace: AbsolutePath
  ): Option[String] = GradleDigest.current(workspace)

  override def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String] = {
    val cmd = List(
      "--console=plain",
      "--init-script",
      initScriptPath.toString,
      "bloopInstall"
    )

    userConfig().gradleScript match {
      case Some(script) =>
        script :: cmd
      case None =>
        embeddedGradleLauncher.toString() :: cmd
    }
  }

  // @tgodzik This this is the wrapper version we specify it as such,
  // since it's hard to determine which version will be used as gradle
  // doesn't save it in any settings
  override def version: String = "5.3.1"

  override def minimumVersion: String = "3.0.0"

  override def recommendedVersion: String = version

  override def toString: String = "Gradle"

  def executableName = "gradle"
}

object GradleBuildTool {
  def isGradleRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath
  ): Boolean = {
    val buildSrc = workspace.toNIO.resolve("buildSrc")
    val filename = path.toNIO.getFileName.toString
    path.toNIO.startsWith(buildSrc) ||
    filename.endsWith(".gradle") ||
    filename.endsWith(".gradle.kts")
  }
}
