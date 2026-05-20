package scala.meta.internal.builds

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.importer.MavenMbtImporter
import scala.meta.io.AbsolutePath

case class MavenBuildTool(
    userConfig: () => UserConfiguration,
    override val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    ec: ExecutionContext,
) extends MavenMbtImporter(projectRoot, shellRunner, userConfig)(ec)
    with BuildTool
    with BloopInstallProvider
    with VersionRecommendation {

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
    mavenBaseCommand() ::: command
  }

  override def mavenBaseCommand(): List[String] =
    userConfig().mavenScript match {
      case Some(script) => List(script)
      case None =>
        writeProperties()
        List(
          JavaBinary(userConfig().javaHome),
          "-Dfile.encoding=UTF-8",
          s"-Dmaven.multiModuleProjectDirectory=$projectRoot",
          s"-Dmaven.home=$tempDir",
          "-cp",
          embeddedMavenLauncher.toString(),
          "org.apache.maven.wrapper.MavenWrapperMain",
        )
    }

  override def isBuildRelated(path: AbsolutePath): Boolean =
    MavenBuildTool.isMavenRelatedPath(projectRoot, path)

  override def digest(workspace: AbsolutePath): Option[String] =
    MavenDigest.current(projectRoot)

  override def minimumVersion: String = "3.5.2"

  override def recommendedVersion: String = version

  override def version: String = "3.9.9"

  override def toString(): String = "Maven"

  def executableName = MavenBuildTool.name
}

object MavenBuildTool {
  def name = "mvn"
  def isMavenRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean = {
    val nio = path.toNIO
    val ws = workspace.toNIO
    nio.startsWith(ws) &&
    (path.filename == "pom.xml" ||
      nio.startsWith(ws.resolve(".mvn")))
  }
}
