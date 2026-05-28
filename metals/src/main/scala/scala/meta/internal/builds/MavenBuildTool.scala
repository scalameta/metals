package scala.meta.internal.builds

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.internal.metals.mbt.importer.MavenMbtImporter
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass

case class MavenBuildTool(
    userConfig: () => UserConfiguration,
    override val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    ec: ExecutionContext,
) extends MavenMbtImporter(projectRoot, shellRunner, userConfig)(ec)
    with BuildTool
    with BloopInstallProvider
    with VersionRecommendation
    with MbtDebugLauncher {

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

  private val mvnwName: String = if (Properties.isWin) "mvnw.cmd" else "mvnw"

  private def hasMvnw(workspace: AbsolutePath): Boolean =
    Files.isRegularFile(workspace.resolve(mvnwName).toNIO)

  private def mbtMavenBaseCommand(workspace: AbsolutePath): List[String] =
    if (hasMvnw(workspace)) List(s"./$mvnwName")
    else mavenBaseCommand()

  override def mbtCompileCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
  ): List[String] = {
    val artifactId = {
      val parts = target.name.split(':')
      if (parts.length >= 2) parts(1) else target.name
    }
    mbtMavenBaseCommand(workspace) ::: List(
      "-q"
    ) ::: mavenCliConfigurations(target) ::: List(
      "-pl",
      s":$artifactId",
      "--also-make",
      "install",
      "-DskipTests",
      "-Denforcer.skip=true",
    )
  }

  override def mbtRunCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
  ): List[String] = {
    mbtExecCommand(workspace, target, mainClass, debugAgentFlag = None)
  }

  override def mbtDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: String,
  ): List[String] = {
    mbtExecCommand(workspace, target, mainClass, Some(debugAgentFlag))
  }

  private def mbtExecCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: Option[String],
  ): List[String] = {
    val moduleArgs =
      mavenModuleDirectory(target)
        .map(_.resolve("pom.xml"))
        .filter(_.isFile) match {
        case Some(pom) =>
          List("-f", pom.toString())
        case _ => Nil
      }
    val jvmOpts =
      (debugAgentFlag.toList ::: MbtDebugLauncher
        .listOrNil(mainClass.getJvmOptions))
        .mkString(" ")
    val appArgs =
      MbtDebugLauncher.listOrNil(mainClass.getArguments).mkString(" ")
    val execArgs =
      s"$jvmOpts -classpath %classpath ${mainClass.getClassName} $appArgs".trim
    mbtMavenBaseCommand(workspace) ::: List(
      "-q"
    ) ::: mavenCliConfigurations(target) ::: moduleArgs ::: List(
      "exec:exec",
      "-Dexec.executable=java",
      s"-Dexec.args=$execArgs",
    )
  }

  private def mavenModuleDirectory(target: MbtTarget): Option[AbsolutePath] =
    target.configurations
      .find(_.startsWith(MavenBuildTool.projectDirPrefix))
      .map(c =>
        AbsolutePath(
          java.nio.file.Paths
            .get(c.substring(MavenBuildTool.projectDirPrefix.length))
        )
      )

  private def mavenCliConfigurations(target: MbtTarget): List[String] =
    target.configurations.toList.flatMap {
      case p if p.startsWith(MavenBuildTool.profilesPrefix) =>
        List("-P", p.substring(MavenBuildTool.profilesPrefix.length))
      case _ => Nil
    }
}

object MavenBuildTool {
  def name = "mvn"

  val projectDirPrefix = "projectDir="
  val profilesPrefix = "profiles="

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
