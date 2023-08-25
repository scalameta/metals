package scala.meta.internal.builds

import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

/**
 * Detects what build tool is used in this workspace.
 *
 * Although we only support a limited set of build tools, knowing
 * what build tool is used in the workspace helps to produce better errors
 * for people using unsupported build tools. For example: "Gradle is not supported"
 * instead of "Unsupported build tool".
 *
 * @param bspGlobalDirectories Directories for user and system installed BSP connection
 *                            details according to BSP spec:
 *                            https://build-server-protocol.github.io/docs/server-discovery.html#default-locations-for-bsp-connection-files
 */
final class BuildTools(
    workspace: AbsolutePath,
    bspGlobalDirectories: List[AbsolutePath],
    userConfig: () => UserConfiguration,
    explicitChoiceMade: () => Boolean,
) {
  private val lastDetectedBuildTools = new AtomicReference(Set.empty[String])
  // project root set explicitly by the user, takes precedence
  val projectRoot: AtomicReference[Option[AbsolutePath]] =
    new AtomicReference(None)

  def setProjectRoot(path: AbsolutePath): Unit = projectRoot.set(Some(path))
  // NOTE: We do a couple extra check here before we say a workspace with a
  // `.bsp` is auto-connectable, and we ensure that a user has explicitly chosen
  // to use another build server besides Bloop or it's a BSP server for a build
  // tool we don't support. If this isn't done, it causes unexpected warnings
  // since if a `.bsp/<something>.json` exists before a `.bloop` one does in a
  // workspace with a build tool we support, we will attempt to autoconnect to
  // Bloop since Metals thinks it's in state that's auto-connectable before the
  // user is even prompted.
  def isAutoConnectable: Boolean = {
    isBloop || (isBsp && all.isEmpty) || (isBsp && explicitChoiceMade()) || (isBsp && isBazel)
  }
  def bloopProject: Option[AbsolutePath] =
    searchForBuildTool(root => hasJsonFile(root.resolve(".bloop")))
  def isBloop: Boolean = bloopProject.isDefined
  def isBsp: Boolean = {
    hasJsonFile(workspace.resolve(".bsp")) ||
    bspGlobalDirectories.exists(hasJsonFile)
  }
  private def hasJsonFile(dir: AbsolutePath): Boolean = {
    dir.list.exists(_.extension == "json")
  }

  // Returns true if there's a build.sbt file or project/build.properties with sbt.version
  def sbtProject: Option[AbsolutePath] = searchForBuildTool { root =>
    root.resolve("build.sbt").isFile || {
      val buildProperties =
        root.resolve("project").resolve("build.properties")
      buildProperties.isFile && {
        val props = new Properties()
        val in = Files.newInputStream(buildProperties.toNIO)
        try props.load(in)
        finally in.close()
        props.getProperty("sbt.version") != null
      }
    }
  }
  def isSbt: Boolean = sbtProject.isDefined
  def millProject: Option[AbsolutePath] = searchForBuildTool(
    _.resolve("build.sc").isFile
  )
  def isMill: Boolean = millProject.isDefined
  def scalaCliProject: Option[AbsolutePath] =
    searchForBuildTool(_.resolve("project.scala").isFile)
      .orElse {
        ScalaCliBspScope.scalaCliBspRoot(workspace) match {
          case Nil => None
          case path :: Nil if path.isFile => Some(path.parent)
          case path :: Nil =>
            scribe.info(s"path: $path")
            Some(path)
          case _ => Some(workspace)
        }
      }

  def gradleProject: Option[AbsolutePath] = {
    val defaultGradlePaths = List(
      "settings.gradle",
      "settings.gradle.kts",
      "build.gradle",
      "build.gradle.kts",
    )
    searchForBuildTool(root =>
      defaultGradlePaths.exists(root.resolve(_).isFile)
    )
  }
  def isGradle: Boolean = gradleProject.isDefined
  def mavenProject: Option[AbsolutePath] = searchForBuildTool(
    _.resolve("pom.xml").isFile
  )
  def isMaven: Boolean = mavenProject.isDefined
  def pantsProject: Option[AbsolutePath] = searchForBuildTool(
    _.resolve("pants.ini").isFile
  )
  def isPants: Boolean = pantsProject.isDefined
  def bazelProject: Option[AbsolutePath] = searchForBuildTool(
    _.resolve("WORKSPACE").isFile
  )
  def isBazel: Boolean = bazelProject.isDefined

  private def searchForBuildTool(
      isProjectRoot: AbsolutePath => Boolean
  ): Option[AbsolutePath] = {
    projectRoot.get() match {
      case Some(root) => if (isProjectRoot(root)) Some(root) else None
      case None =>
        def recIsProjectRoot(
            path: AbsolutePath,
            level: Int = 0,
        ): Option[AbsolutePath] =
          // we skip `.scala-build` and `project`, which both contain .bloop
          if (
            path.isDirectory && !path.toNIO.filename.startsWith(
              "."
            ) && path.toNIO.filename != "project"
          ) {
            if (isProjectRoot(path)) Some(path)
            else if (level < 1)
              path.toNIO
                .toFile()
                .listFiles()
                .collectFirst(root =>
                  recIsProjectRoot(AbsolutePath(root), level + 1) match {
                    case Some(root) => root
                  }
                )
            else None
          } else None
        recIsProjectRoot(workspace)
    }
  }
  def allAvailable: List[BuildTool] = {
    List(
      SbtBuildTool(workspaceVersion = None, workspace, userConfig),
      GradleBuildTool(userConfig, workspace),
      MavenBuildTool(userConfig, workspace),
      MillBuildTool(userConfig, workspace),
      ScalaCliBuildTool(workspace, workspace, userConfig),
    )
  }

  def all: List[String] = {
    val buf = List.newBuilder[String]
    if (isBloop) buf += BloopServers.name
    if (isSbt) buf += "sbt"
    if (isMill) buf += "Mill"
    if (isGradle) buf += "Gradle"
    if (isMaven) buf += "Maven"
    if (isPants) buf += "Pants"
    if (isBazel) buf += "Bazel"
    buf.result()
  }

  def isEmpty: Boolean = {
    all.isEmpty
  }

  def loadSupported(): List[BuildTool] = {
    val buf = List.newBuilder[BuildTool]

    sbtProject.foreach(buf += SbtBuildTool(_, userConfig))
    gradleProject.foreach(buf += GradleBuildTool(userConfig, _))
    mavenProject.foreach(buf += MavenBuildTool(userConfig, _))
    millProject.foreach(buf += MillBuildTool(userConfig, _))
    scalaCliProject.foreach(buf += ScalaCliBuildTool(workspace, _, userConfig))

    buf.result()
  }

  override def toString: String = {
    val names = all.mkString("+")
    if (names.isEmpty) "<no build tool>"
    else names
  }

  def isBuildRelated(
      path: AbsolutePath
  ): Option[String] = {
    if (sbtProject.exists(SbtBuildTool.isSbtRelatedPath(_, path)))
      Some(SbtBuildTool.name)
    else if (gradleProject.exists(GradleBuildTool.isGradleRelatedPath(_, path)))
      Some(GradleBuildTool.name)
    else if (mavenProject.exists(MavenBuildTool.isMavenRelatedPath(_, path)))
      Some(MavenBuildTool.name)
    else if (isMill && MillBuildTool.isMillRelatedPath(path))
      Some(MillBuildTool.name)
    else None
  }

  def initialize(): Set[String] = {
    lastDetectedBuildTools.getAndSet(
      loadSupported().map(_.executableName).toSet
    )
  }

  def newBuildTool(buildTool: String): Boolean = {
    val before = lastDetectedBuildTools.getAndUpdate(_ + buildTool)
    !before.contains(buildTool)
  }
}

object BuildTools {
  def default(workspace: AbsolutePath = PathIO.workingDirectory): BuildTools =
    new BuildTools(
      workspace,
      Nil,
      () => UserConfiguration(),
      explicitChoiceMade = () => false,
    )
}
