package scala.meta.internal.builds

import scala.meta.internal.metals.{MetalsServerConfig, UserConfiguration}
import scala.meta.io.AbsolutePath
import scala.tools.nsc.Properties
import scala.meta.internal.metals.MetalsEnrichments._

case class MavenBuildTool() extends BuildTool {

  private lazy val embeddedMavenLauncher: AbsolutePath = {
    val mavenWrapper =
      if (Properties.isWin) "mvnw.cmd"
      else "mvnw"
    val out = BuildTool.copyFromResource(tempDir, mavenWrapper)
    out.toFile.setExecutable(true)
    Set(
      s"maven-wrapper.jar",
      "maven-wrapper.properties",
      "MavenWrapperDownloader.java"
    ).foreach { fileName =>
      BuildTool.copyFromResource(
        tempDir,
        s"mvn/wrapper/$fileName",
        Some(s".mvn/wrapper/$fileName")
      )
    }
    AbsolutePath(out)
  }

  def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String] = {
    import scala.meta.internal.metals.BuildInfo
    val command =
      List(
        s"ch.epfl.scala:maven-bloop_2.10:${BuildInfo.bloopVersion}:bloopInstall",
        "-DdownloadSources=true"
      )
    userConfig().mavenScript match {
      case Some(script) =>
        script :: command
      case None =>
        embeddedMavenLauncher.toString() :: command
    }
  }

  def digest(workspace: AbsolutePath): Option[String] =
    MavenDigest.current(workspace)

  override def minimumVersion: String = "3.5.2"

  override def recommendedVersion: String = version

  override def version: String = "3.6.1"

  override def toString(): String = "Maven"

  def executableName = "mvn"
}

object MavenBuildTool {
  def isMavenRelatedPath(workspace: AbsolutePath, path: AbsolutePath) = {
    path.toNIO.startsWith(workspace.toNIO) && path.filename == "pom.xml"
  }
}
