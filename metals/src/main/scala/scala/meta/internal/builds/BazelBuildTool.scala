package scala.meta.internal.builds

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Tables
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.mbt.MbtDebugLauncher
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.internal.metals.mbt.importer.BazelMbtImporter
import scala.meta.internal.metals.mbt.importer.BazelQuery
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.ScalaMainClass
import ch.epfl.scala.bsp4j.ScalaTestSuites
import coursier.Dependency

case class BazelBuildTool(
    userConfig: () => UserConfiguration,
    override val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    ec: ExecutionContext,
    languageClient: Option[MetalsLanguageClient] = None,
    tables: Option[Tables] = None,
) extends BazelMbtImporter(
      projectRoot,
      shellRunner,
      userConfig,
      languageClient,
      tables,
    )(ec)
    with BuildTool
    with BuildServerProvider
    with VersionRecommendation
    with MbtDebugLauncher {

  private implicit val executionContext: ExecutionContext = ec

  override def digest(workspace: AbsolutePath): Option[String] = {
    BazelDigest.current(projectRoot)
  }

  def createBspFileArgs(workspace: AbsolutePath): Option[List[String]] =
    Option.when(BazelBuildTool.workspaceSupportsBsp(projectRoot))(composeArgs())

  private def composeArgs(): List[String] = {
    val classpathSeparator = java.io.File.pathSeparator
    val classpath = Embedded
      .downloadDependency(BazelBuildTool.dependency)
      .mkString(classpathSeparator)
    List(
      JavaBinary(userConfig().javaHome),
      "-classpath",
      classpath,
      BazelBuildTool.mainClass,
    ) ++ BazelBuildTool.projectViewArgs(projectRoot)
  }

  override def minimumVersion: String = "5.0.0"

  override def recommendedVersion: String = version

  override def version: String = BazelBuildTool.resolveBazelVersion(projectRoot)

  override def toString: String = "bazel"

  override def executableName = BazelBuildTool.name

  override val forcesBuildServer = true

  override def buildServerName: String = BazelBuildTool.bspName

  override def shouldRegenerateBspJson(
      currentVersion: String,
      workspace: AbsolutePath,
  ): Boolean = {
    currentVersion != BazelBuildTool.bspVersion
  }

  override def ensurePrerequisites(workspace: AbsolutePath): Unit = {
    workspace.list.find(_.filename.endsWith(".bazelproject")) match {
      // project view cannot be empty, so we need to create a fallback
      case Some(path) if path.readText.trim.isEmpty =>
        path.writeText(BazelBuildTool.fallbackProjectView)
      case _ =>
    }
  }

  override def mbtCompileCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
  ): List[String] =
    List("bazel", "build") ::: bazelBuildTargets(target)

  override def mbtRunCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
  ): List[String] =
    mbtRunCommand(target, mainClass, debugAgentFlag = None)

  override def mbtDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: String,
  ): List[String] =
    mbtRunCommand(target, mainClass, debugAgentFlag = Some(debugAgentFlag))

  private def mbtRunCommand(
      target: MbtTarget,
      mainClass: ScalaMainClass,
      debugAgentFlag: Option[String],
  ): List[String] = {
    val jvmFlags =
      debugAgentFlag.toList ::: MbtDebugLauncher
        .listOrNil(mainClass.getJvmOptions)
    val appArgs = MbtDebugLauncher.listOrNil(mainClass.getArguments)
    List(
      "bazel",
      "run",
      "--ui_event_filters=-info,-stderr",
      "--noshow_progress",
      bazelRunTarget(target),
      "--",
    ) ::: jvmFlags.map(flag => s"--jvm_flag=$flag") ::: appArgs
  }

  override def mbtTestCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]] =
    resolveTestRunTargets(workspace, target, sourceFiles).map { runTargets =>
      mbtTestExecCommand(runTargets, target, testSuites, debugAgentFlag = None)
    }

  override def mbtTestDebugCommand(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: String,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]] =
    resolveTestRunTargets(workspace, target, sourceFiles).map { runTargets =>
      mbtTestExecCommand(
        runTargets,
        target,
        testSuites,
        debugAgentFlag = Some(debugAgentFlag),
      )
    }

  override def supportsForkedTestDebug: Boolean = true

  override def mbtTestDebugCommandWithPort(
      workspace: AbsolutePath,
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      sourceFiles: Seq[AbsolutePath],
  ): Int => Future[List[String]] = {
    val resolvedRunTargets =
      resolveTestRunTargets(workspace, target, sourceFiles)
    (port: Int) =>
      resolvedRunTargets.map { runTargets =>
        mbtTestExecCommand(
          runTargets,
          target,
          testSuites,
          debugAgentFlag = None,
        ) ::: List(
          "--nocache_test_results",
          "--test_output=streamed",
          "--test_strategy=exclusive",
          s"--test_arg=--wrapper_script_flag=--debug=$port",
        )
      }
  }

  private def resolveTestRunTargets(
      workspace: AbsolutePath,
      target: MbtTarget,
      sourceFiles: Seq[AbsolutePath],
  ): Future[List[String]] =
    target.configurations.toList match {
      case Nil => Future.successful(List(target.name))
      case single :: Nil => Future.successful(List(single))
      case multiple =>
        val filenames = sourceFiles.map(_.filename).distinct
        if (filenames.isEmpty) Future.successful(List(multiple.head))
        else {
          val setExpr =
            s"set(${multiple.flatMap(BazelQuery.quoteTarget).mkString(" ")})"
          val queryStr = filenames
            .map(filename => s"attr(srcs, $filename, $setExpr)")
            .mkString(" union ")
          val env =
            BazelQuery.Env(projectRoot, shellRunner, userConfig().javaHome)
          BazelQuery(queryStr, BazelQuery.OutputMode.Label)
            .run(env)(ec)
            .recover { case e =>
              scribe.warn(
                s"bazel-mbt: failed to resolve test targets for ${target.name}, falling back to ${multiple.head}: ${e.getMessage}"
              )
              ""
            }
            .map { result =>
              val resolved = result.linesIterator.filter(_.nonEmpty).toList
              if (resolved.nonEmpty) resolved else List(multiple.head)
            }
        }
    }

  private def mbtTestExecCommand(
      runTargets: List[String],
      target: MbtTarget,
      testSuites: ScalaTestSuites,
      debugAgentFlag: Option[String],
  ): List[String] = {
    val jvmFlags =
      debugAgentFlag.toList ::: MbtDebugLauncher
        .listOrNil(testSuites.getJvmOptions)
    val suites = MbtDebugLauncher.listOrNil(testSuites.getSuites)
    val testFilter = suites.flatMap { suite =>
      val className = suite.getClassName
      val tests = MbtDebugLauncher.listOrNil(suite.getTests)
      if (tests.isEmpty) List(className)
      else tests.map(test => s"$className#$test")
    }
    val testFilterArgs =
      if (testFilter.isEmpty) Nil
      else List(s"--test_filter=${testFilter.mkString(",")}")
    val jvmFlagsArgs =
      jvmFlags.map(flag => s"--test_arg=--wrapper_script_flag=--jvm_flag=$flag")
    List(
      "bazel", "test", "--ui_event_filters=-info,-stderr,-warning",
      "--noshow_progress", "--test_output=all",
    ) ::: runTargets ::: testFilterArgs ::: jvmFlagsArgs
  }

  private def bazelBuildTargets(target: MbtTarget): List[String] =
    target.configurations.toList match {
      case Nil => List(target.name)
      case targets => targets
    }

  private def bazelRunTarget(target: MbtTarget): String =
    target.configurations.headOption.getOrElse(target.name)

}

object BazelBuildTool {
  val name: String = "bazel"
  val bspName: String = "bazelbsp"
  val bspVersion: String = "4.0.3"
  val defaultBazelVersion = "8.2.1"

  def resolveBazelVersion(projectRoot: AbsolutePath): String = {
    val bazelVersionFile = projectRoot.resolve(".bazelversion")
    if (bazelVersionFile.exists && bazelVersionFile.isFile) {
      val version = bazelVersionFile.readText.trim()
      if (version.nonEmpty) version else defaultBazelVersion
    } else {
      defaultBazelVersion
    }
  }

  def getScalaRulesName(projectRoot: AbsolutePath): Option[String] = {
    ScalaRulesetFinderHeuristic(projectRoot).guessRulesetName()
  }

  val mainClass = "org.jetbrains.bsp.bazel.install.Install"

  val dependency: Dependency = Embedded.dependencyOf(
    "org.virtuslab",
    "bazel-bsp",
    bspVersion,
  )

  private def hasProjectView(dir: AbsolutePath): Option[AbsolutePath] =
    Some(dir.resolve(".bazelproject"))
      .filter(_.isFile)
      .orElse(dir.list.find(_.filename.endsWith(".bazelproject")))

  def enabledRules(projectRoot: AbsolutePath): List[String] = {
    val scalaRules = getScalaRulesName(projectRoot)
    List(scalaRules.getOrElse("rules_scala"), "rules_java", "rules_jvm")
  }

  def fallbackProjectView: String = {
    s"""|targets:
        |    //...
        |
        |build_manual_targets: false
        |
        |derive_targets_from_directories: false
        |
        |""".stripMargin
  }

  def existingProjectView(
      projectRoot: AbsolutePath
  ): Option[AbsolutePath] =
    List(projectRoot, projectRoot.resolve("ijwb"), projectRoot.resolve(".ijwb"))
      .filter(_.isDirectory)
      .flatMap(hasProjectView)
      .headOption

  def projectViewArgs(projectRoot: AbsolutePath): List[String] = {
    existingProjectView(projectRoot) match {
      // if project view is empty nothing will work, since no targets are specified
      case Some(projectView) if projectView.readText.trim().isEmpty =>
        projectView.writeText(fallbackProjectView)
        List("-p", projectView.toRelative(projectRoot).toString())
      case Some(projectView) =>
        List("-p", projectView.toRelative(projectRoot).toString())
      case None =>
        List(
          "-t",
          "//...",
        )
    }
  }

  def workspaceSupportsBsp(projectRoot: AbsolutePath): Boolean = {
    val bzlProjectRootFiles = Set("WORKSPACE", "MODULE.bazel")
    projectRoot.list.exists { file =>
      bzlProjectRootFiles.contains(file.filename)
    }
  }

  def isBazelRelatedPath(
      workspace: AbsolutePath,
      path: AbsolutePath,
  ): Boolean =
    path.toNIO.startsWith(workspace.toNIO) &&
      path.isBazelRelatedPath &&
      !path.isInBazelBspDirectory(workspace)
}
