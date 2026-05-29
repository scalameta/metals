package scala.meta.internal.builds
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.util.Properties

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.internal.metals.mbt.importer.GradleMbtImporter
import scala.meta.internal.mtags.MD5
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites
import coursier.MavenRepository
import coursier.Repositories
import coursier.Repository
import coursier.core.Authentication
import coursier.ivy.IvyRepository

case class GradleBuildTool(
    userConfig: () => UserConfiguration,
    override val projectRoot: AbsolutePath,
)(implicit ec: ExecutionContext)
    extends GradleMbtImporter(projectRoot)
    with BuildTool
    with BloopInstallProvider
    with VersionRecommendation
    with MbtDebugLauncher {

  private val initScriptName = "init-script.gradle"
  private val gradleBloopVersion = BuildInfo.gradleBloopVersion
  private def initScript = {
    s"""
       |initscript {
       |${GradleBuildTool.toGradleRepositories(Embedded.repositories)}
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

  private def projectGradleLauncher: AbsolutePath = {
    projectRoot.resolve(gradleWrapper)
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

    gradleBaseCommand() ::: cmd
  }

  // @tgodzik This is the wrapper version we specify it as such,
  // since it's hard to determine which version will be used as gradle
  // doesn't save it in any settings
  override def version: String = "7.5.0"

  override def minimumVersion: String = "5.0.0"

  override def recommendedVersion: String = version

  override def toString: String = "Gradle"

  override def executableName = GradleBuildTool.name

  override def mbtCompileCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
  ): List[String] =
    gradleBaseCommand() ::: List(
      "--console=plain",
      gradleTask(target, gradleCompileTask(target)),
    )

  override def mbtRunCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
  ): List[String] =
    gradleRunCommand(target, mainClass, debugAgentFlag = None)

  override def mbtDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: String,
  ): List[String] =
    gradleRunCommand(target, mainClass, Some(debugAgentFlag))

  private def gradleBaseCommand(): List[String] =
    userConfig().gradleScript match {
      case Some(script) => List(script)
      case None =>
        val projectGradle = projectGradleLauncher
        if (projectGradle.isFile) List(projectGradle.toString())
        else List(embeddedGradleLauncher.toString())
    }

  private def gradleTask(target: MbtTarget, task: String): String =
    target.projectPath match {
      case Some(":") | Some("") | None => task
      case Some(path) if path.endsWith(":") => s"$path$task"
      case Some(path) => s"$path:$task"
    }

  private def gradleCompileTask(target: MbtTarget): String = {
    val hasTestSources =
      target.sources.exists(_.replace('\\', '/').contains("/test/"))
    if (target.isTestTarget || hasTestSources) "testClasses"
    else "classes"
  }

  private def gradleRunCommand(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: Option[String],
  ): List[String] = {
    gradleBaseCommand() ::: List(
      "--console=plain",
      "-q",
      "--init-script",
      gradleRunInitScript(target, mainClass, debugAgentFlag).toString,
      gradleTask(target, GradleBuildTool.metalsRunTask),
    )
  }

  private def gradleInitScript(
      target: MbtTarget,
      taskName: String,
      errorContext: String,
  )(taskCode: String => String): Path = {
    val projectBlock = target.projectPath match {
      case Some(path) =>
        val gradlePath =
          if (path == ":" || path.isEmpty) ":"
          else if (path.startsWith(":")) path
          else s":$path"

        val projectLookup =
          if (gradlePath == ":") "gradle.rootProject"
          else
            s"gradle.rootProject.findProject(${GradleBuildTool.groovyString(gradlePath)})"

        s"""|  def project = $projectLookup
            |  if (project == null) {
            |    throw new GradleException("Could not find Gradle project ${GradleBuildTool.groovyString(gradlePath)}")
            |  }
            |${taskCode(gradlePath)}""".stripMargin
      case None =>
        s"""|  throw new GradleException("Missing Gradle project path for Metals $errorContext")
            |""".stripMargin
    }
    val script =
      s"""|gradle.projectsEvaluated {
          |$projectBlock
          |}
          |""".stripMargin

    val digest = MD5.compute(script)
    Files.write(
      tempDir.resolve(s"$taskName-$digest.gradle"),
      script.getBytes(StandardCharsets.UTF_8),
    )
  }

  private def gradleRunInitScript(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: Option[String],
  ): Path = {
    val jvmOptions =
      debugAgentFlag.toList ::: MbtDebugLauncher.listOrNil(
        mainClass.getJvmOptions
      )
    val args = MbtDebugLauncher.listOrNil(mainClass.getArguments)

    gradleInitScript(target, GradleBuildTool.metalsRunTask, "run/debug") {
      gradlePath =>
        s"""|  def sourceSets = project.extensions.findByName('sourceSets')
            |  def main = sourceSets?.findByName('main')
            |  if (main == null) {
            |    throw new GradleException("Project ${GradleBuildTool.groovyString(gradlePath)} does not have a main source set")
            |  }
            |  project.tasks.register('${GradleBuildTool.metalsRunTask}', JavaExec) { task ->
            |    task.group = 'metals'
            |    task.classpath = main.runtimeClasspath
            |    if (task.hasProperty('mainClass')) {
            |      task.mainClass.set(${GradleBuildTool.groovyString(mainClass.getClassName)})
            |    } else {
            |      task.main = ${GradleBuildTool.groovyString(mainClass.getClassName)}
            |    }
            |    task.jvmArgs(${GradleBuildTool.groovyList(jvmOptions)})
            |    task.args(${GradleBuildTool.groovyList(args)})
            |  }""".stripMargin
    }
  }

  override def mbtTestCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
  ): List[String] =
    gradleTestCommand(target, testSuites, debugAgentFlag = None)

  override def mbtTestDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: String,
  ): List[String] =
    gradleTestCommand(target, testSuites, Some(debugAgentFlag))

  override def supportsForkedTestDebug: Boolean = true

  override def mbtTestDebugCommandWithPort(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
  ): Int => List[String] = { port =>
    val debugAgentFlag =
      s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:$port"
    gradleTestCommand(target, testSuites, Some(debugAgentFlag))
  }

  private def gradleTestCommand(
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: Option[String],
  ): List[String] = {
    val jvmOptions =
      debugAgentFlag.toList ::: MbtDebugLauncher.listOrNil(
        testSuites.getJvmOptions
      )
    val initScriptArgs =
      if (jvmOptions.isEmpty) Nil
      else
        List("--init-script", gradleTestInitScript(target, jvmOptions).toString)

    gradleBaseCommand() ::: List(
      "--console=plain"
    ) ::: initScriptArgs ::: List(
      gradleTask(target, "test")
    ) ::: gradleTestFilterArgs(testSuites)
  }

  private def gradleTestFilterArgs(
      testSuites: ScalaTestSuites
  ): List[String] = {
    val filters =
      MbtDebugLauncher.listOrNil(testSuites.getSuites).flatMap { suite =>
        val className = suite.getClassName
        val tests = MbtDebugLauncher.listOrNil(suite.getTests)
        if (tests.isEmpty) List(className)
        else tests.map(test => s"$className.$test")
      }
    filters.flatMap(filter => List("--tests", filter))
  }

  private def gradleTestInitScript(
      target: MbtTarget,
      jvmOptions: List[String],
  ): Path =
    gradleInitScript(target, GradleBuildTool.metalsTestTask, "test") { _ =>
      s"""|  project.tasks.withType(Test).configureEach { task ->
          |    task.jvmArgs(${GradleBuildTool.groovyList(jvmOptions)})
          |  }""".stripMargin
    }

}

object GradleBuildTool {
  def name = "gradle"

  private val metalsRunTask = "__metalsRun"
  private val metalsTestTask = "__metalsTest"

  private def groovyList(values: List[String]): String =
    values.map(groovyString).mkString("[", ", ", "]")

  private def groovyString(value: String): String =
    "'" + value
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\n", "\\n")
      .replace("\r", "\\r") + "'"

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
    def authString(cr: Option[Authentication]) =
      cr match {
        case Some(auth) =>
          val user = auth.userOpt match {
            case Some(user) => s"username \"$user\""
            case None => ""
          }
          val password = auth.passwordOpt match {
            case Some(password) => s"password \"$password\""
            case None => ""
          }
          if (user.isEmpty && password.isEmpty) ""
          else
            s"""|
                |      credentials {
                |        ${user}
                |        ${password}
                |      }""".stripMargin
        case None => ""
      }

    repos.collect {
      case mr: MavenRepository if mr != Repositories.central =>
        // filter central etc.
        s"""|    maven {
            |      url "${mr.root}"${authString(mr.authentication)}
            |    }""".stripMargin

      case ir: IvyRepository =>
        ir.pattern.string.split("\\/\\[").toList match {
          case url :: rest => {
            val layout = "[" ++ rest.mkString("/[")
            s"""|    ivy {
                |      url "${url}"
                |      patternLayout {
                |        artifact "${layout}"
                |      }${authString(ir.authentication)}
                |    }""".stripMargin
          }
          case Nil => ""
        }
      case mr if mr == Repositories.central => "    mavenCentral()"
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
