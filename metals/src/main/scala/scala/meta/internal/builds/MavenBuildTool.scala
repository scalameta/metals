package scala.meta.internal.builds

import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.JavaBinary

case class MavenBuildTool(userConfig: () => UserConfiguration)
    extends BloopPluginBuildTool {

  private lazy val embeddedMavenLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "maven-wrapper.jar")
    AbsolutePath(out)
  }

  private def writeProperties(): AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "maven-wrapper.properties")
    AbsolutePath(out)
  }

  def args(workspace: AbsolutePath): List[String] = {
    def command(versionToUse: String) =
      List(
        s"ch.epfl.scala:maven-bloop_2.10:$versionToUse:bloopInstall",
        "-DdownloadSources=true"
      )
    userConfig().mavenScript match {
      case Some(script) =>
        script :: command(userConfig().currentBloopVersion)
      case None =>
        writeProperties()
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Dfile.encoding=UTF-8",
          s"-Dmaven.multiModuleProjectDirectory=$workspace",
          s"-Dmaven.home=$tempDir"
        )

        val jarArgs = List(
          "-jar",
          embeddedMavenLauncher.toString()
        )
        List(
          javaArgs,
          jarArgs,
          command(userConfig().currentBloopVersion)
        ).flatten
    }
  }

  def digest(workspace: AbsolutePath): Option[String] = {
    MavenDigest.current(workspace)
  }

  override def minimumVersion: String = "3.5.2"

  override def recommendedVersion: String = version

  override def version: String = "3.6.1"

  override def toString(): String = "Maven"

  def executableName = "mvn"
}

object MavenBuildTool {
  def isMavenRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath
  ): Boolean = {
    path.toNIO.startsWith(workspace.toNIO) && path.filename == "pom.xml"
  }
}
