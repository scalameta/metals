package scala.meta.internal.builds

import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.JavaBinary
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

import coursierapi.Dependency

case class BazelBuildTool(
    userConfig: () => UserConfiguration,
    projectRoot: AbsolutePath,
) extends BuildTool
    with BuildServerProvider
    with VersionRecommendation {

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

  override def minimumVersion: String = "3.0.0"

  override def recommendedVersion: String = version

  override def version: String = BazelBuildTool.version

  override def toString: String = "bazel"

  override def executableName = BazelBuildTool.name

  override val forcesBuildServer = true

  override def buildServerName: String = BazelBuildTool.bspName

  override def shouldRegenerateBspJson(
      currentVersion: String,
      workspace: AbsolutePath,
  ): Boolean = {
    currentVersion != BazelBuildTool.version
  }

  override def ensurePrerequisites(workspace: AbsolutePath): Unit = {
    workspace.list.find(_.filename.endsWith(".bazelproject")) match {
      // project view cannot be empty, so we need to create a fallback
      case Some(path) if path.readText.trim.isEmpty =>
        path.writeText(BazelBuildTool.fallbackProjectView)
      case _ =>
    }
  }

}

object BazelBuildTool {
  val name: String = "bazel"
  val bspName: String = "bazelbsp"
  val version: String = "4.0.1"

  val mainClass = "org.jetbrains.bsp.bazel.install.Install"

  val dependency: Dependency = Dependency.of(
    "org.virtuslab",
    "bazel-bsp",
    version,
  )

  private def hasProjectView(dir: AbsolutePath): Option[AbsolutePath] =
    Some(dir.resolve(".bazelproject"))
      .filter(_.isFile)
      .orElse(dir.list.find(_.filename.endsWith(".bazelproject")))

  val fallbackProjectView: String = {
    """|targets:
       |    //...
       |
       |build_manual_targets: false
       |
       |derive_targets_from_directories: false
       |
       |enabled_rules:
       |    io_bazel_rules_scala
       |    rules_java
       |    rules_jvm
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
          "-t", "//...", "-enabled-rules", "io_bazel_rules_scala", "rules_java",
          "rules_jvm",
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
