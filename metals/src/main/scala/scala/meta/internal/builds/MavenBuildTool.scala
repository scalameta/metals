package scala.meta.internal.builds

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

case class MavenBuildTool(userConfig: () => UserConfiguration)
    extends BuildTool
    with BloopInstallProvider {

  private lazy val embeddedMavenLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "maven-wrapper.jar")
    AbsolutePath(out)
  }

  private def writeProperties(): AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "maven-wrapper.properties")
    AbsolutePath(out)
  }

  private lazy val bloopMavenPluginVersion = BuildInfo.mavenBloopVersion

  def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val command =
      List(
        "generate-sources",
        s"ch.epfl.scala:bloop-maven-plugin:$bloopMavenPluginVersion:bloopInstall",
        "-DdownloadSources=true",
      )
    userConfig().mavenScript match {
      case Some(script) =>
        script :: command
      case None =>
        writeProperties()
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Dfile.encoding=UTF-8",
          s"-Dmaven.multiModuleProjectDirectory=$workspace",
          s"-Dmaven.home=$tempDir",
        )

        val jarArgs = List(
          "-cp",
          embeddedMavenLauncher.toString(),
          "org.apache.maven.wrapper.MavenWrapperMain",
        )
        List(
          javaArgs,
          jarArgs,
          command,
        ).flatten
    }
  }

  def digest(workspace: AbsolutePath): Option[String] = {
    MavenDigest.current(workspace)
  }

  override def minimumVersion: String = "3.5.2"

  override def recommendedVersion: String = version

  override def version: String = "3.8.6"

  override def toString(): String = "Maven"

  def executableName = "mvn"
}

object MavenBuildTool {
  def isMavenRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean = {
    path.toNIO.startsWith(workspace.toNIO) && path.filename == "pom.xml"
  }
}
