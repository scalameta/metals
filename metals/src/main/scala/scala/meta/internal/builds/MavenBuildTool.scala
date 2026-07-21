package scala.meta.internal.builds

import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
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
import ch.epfl.scala.bsp4j.ScalaTestSuites

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
    mbtMavenBaseCommand(projectRoot)

  override def isBuildRelated(path: AbsolutePath): Boolean =
    MavenBuildTool.isMavenRelatedPath(projectRoot, path)

  override def digest(workspace: AbsolutePath): Option[String] =
    MavenDigest.current(projectRoot)

  override def minimumVersion: String = "3.5.2"

  override def recommendedVersion: String = version

  override def version: String = "3.9.9"

  override def toString(): String = "Maven"

  override def executableName = MavenBuildTool.name

  private val mvnwName: String = if (Properties.isWin) "mvnw.cmd" else "mvnw"

  private def hasMvnw(workspace: AbsolutePath): Boolean =
    Files.isRegularFile(workspace.resolve(mvnwName).toNIO)

  private def mbtMavenBaseCommand(workspace: AbsolutePath): List[String] =
    if (hasMvnw(workspace)) List(s"./$mvnwName")
    else
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

  override def mbtCompileCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
  ): List[String] = {
    mbtMavenBaseCommand(workspace) ::: List(
      "-q"
    ) ::: mavenCliConfigurations(target) ::: reactorModuleArgs(target) ::: List(
      "install",
      "-DskipTests",
    ) ::: MavenBuildTool.pluginsToSkip
  }

  private def reactorModuleArgs(target: MbtTarget): List[String] = {
    val parts = target.name.split(':')
    val artifactId = if (parts.length >= 2) parts(1) else target.name
    List("-pl", s":$artifactId", "--also-make")
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
        .filter(_.exists) match {
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
    ) ::: MavenBuildTool.pluginsToSkip
  }

  private def mavenModuleDirectory(target: MbtTarget): Option[AbsolutePath] =
    target.projectPath.map(p => AbsolutePath(Paths.get(p)))

  private def mavenCliConfigurations(target: MbtTarget): List[String] =
    target.configurations.toList

  override def mbtTestCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]] =
    Future.successful(
      mbtTestExecCommand(
        mbtMavenBaseCommand(workspace),
        target,
        testSuites,
      )
    )

  override def mbtTestDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: String,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]] =
    mbtTestDebugCommandWithPort(workspace, target, testSuites, sourceFiles)(
      5005
    )

  override def supportsForkedTestDebug: Boolean = true

  override def mbtTestDebugCommandWithPort(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Int => Future[List[String]] = { port =>
    // Use Surefire's forked JVM with a pre-assigned debug port.
    // This allows proper source mapping since tests run in a separate JVM.
    val debugAgentFlag =
      s"\"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$port\""
    Future.successful(
      mbtTestExecCommand(
        mbtMavenBaseCommand(workspace),
        target,
        testSuites,
        forkedDebugAgentFlag = Some(debugAgentFlag),
      )
    )
  }

  private def mbtTestExecCommand(
      baseCommand: List[String],
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      forkedDebugAgentFlag: Option[String] = None,
  ): List[String] = {
    val moduleArgs =
      mavenModuleDirectory(target)
        .map(_.resolve("pom.xml"))
        .filter(_.exists) match {
        case Some(pom) =>
          List("-f", pom.toString())
        case _ => Nil
      }
    val suites = MbtDebugLauncher.listOrNil(testSuites.getSuites)
    val testFilter = suites.flatMap { suite =>
      val className = suite.getClassName
      val tests = MbtDebugLauncher.listOrNil(suite.getTests)
      if (tests.isEmpty) List(className)
      else tests.map(test => s"$className#$test")
    }
    val testArgs =
      if (testFilter.isEmpty) Nil
      else List(s"-Dtest=${testFilter.mkString(",")}")
    val userJvmOpts = MbtDebugLauncher.listOrNil(testSuites.getJvmOptions)
    val jvmArgs =
      if (userJvmOpts.isEmpty) Nil
      else List(s"-DargLine=${userJvmOpts.mkString(" ")}")
    val debugArgs =
      forkedDebugAgentFlag.map(flag => s"-Dmaven.surefire.debug=$flag").toList
    baseCommand ::: target.configurations.toList ::: moduleArgs ::: List(
      "test"
    ) ::: testArgs ::: jvmArgs ::: debugArgs ::: MavenBuildTool.pluginsToSkip
  }
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

  private val pluginsToSkip = List(
    "-Denforcer.skip=true", "-Dcheckstyle.skip=true", "-Dspotbugs.skip=true",
    "-Drat.skip=true", "-Dpmd.skip=true", "-Dmaven.javadoc.skip=true",
    "-Dspring-javaformat.skip=true", "-Dspotless.check.skip=true",
    "-Djacoco.skip=true", "-Dsonar.skip=true", "-Ddependency-check.skip=true",
    "-Dgpg.skip=true",
  )
}
