package scala.meta.internal.builds

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.bsp.BspServers
import scala.meta.internal.bsp.ScalaCliBspScope
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.io.AbsolutePath

import ujson.ParsingFailedException

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
    charset: Charset,
) {
  private val lastDetectedBuildTools = new AtomicReference(
    Map.empty[String, BuildTool]
  )
  // NOTE: We do a couple extra check here before we say a workspace with a
  // `.bsp` is auto-connectable, and we ensure that a user has explicitly chosen
  // to use another build server besides Bloop or it's a BSP server for a build
  // tool we don't support. If this isn't done, it causes unexpected warnings
  // since if a `.bsp/<something>.json` exists before a `.bloop` one does in a
  // workspace with a build tool we support, we will attempt to autoconnect to
  // Bloop since Metals thinks it's in state that's auto-connectable before the
  // user is even prompted.
  def isAutoConnectable(
      maybeProjectRoot: Option[AbsolutePath] = None
  ): Boolean = {
    maybeProjectRoot
      .map(isBloop)
      .getOrElse(
        isBloop
      ) || (isBsp && all.isEmpty) || (isBsp && explicitChoiceMade())
  }
  def isBloop(root: AbsolutePath): Boolean = hasJsonFile(root.resolve(".bloop"))
  def bloopProject: Option[AbsolutePath] = searchForBuildTool(isBloop)
  def isBloop: Boolean = bloopProject.isDefined
  def isBsp: Boolean = {
    hasJsonFile(workspace.resolve(Directories.bsp)) ||
    customProjectRoot.exists(root =>
      hasJsonFile(root.resolve(Directories.bsp))
    ) ||
    bspGlobalDirectories.exists(hasJsonFile)
  }
  private def hasJsonFile(dir: AbsolutePath): Boolean = {
    dir.list.exists(_.extension == "json")
  }

  def isBazelBsp: Boolean = {
    workspace.resolve(Directories.bazelBsp).isDirectory &&
    BazelBuildTool.existingProjectView(workspace).nonEmpty &&
    isBsp
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
  def millProject: Option[AbsolutePath] = searchForBuildTool(path =>
    path.resolve("build.mill").isFile ||
      path.resolve("build.mill.scala").isFile ||
      path.resolve("build.sc").isFile
  )
  def isMill: Boolean = millProject.isDefined
  def isMillBsp(path: AbsolutePath): Boolean =
    isInBsp(path) && path.filename.contains("mill") &&
      path.filename.endsWith(".json")

  def scalaCliProject: Option[AbsolutePath] =
    searchForBuildTool(_.resolve("project.scala").isFile)
      .orElse {
        try {
          ScalaCliBspScope.scalaCliBspRoot(workspace) match {
            case Nil => None
            case path :: Nil if path.isFile => Some(path.parent)
            case path :: Nil => Some(path)
            case _ => Some(workspace)
          }
        } catch {
          case _: ParsingFailedException =>
            scribe.warn(s"could not parse scala-cli build server configuration")
            None
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
  def bazelProject: Option[AbsolutePath] = searchForBuildTool(
    _.resolve("WORKSPACE").isFile
  )
  def isBazel: Boolean = bazelProject.isDefined

  def isInBsp(path: AbsolutePath): Boolean =
    path.isFile && path.parent.filename == ".bsp" &&
      path.filename.endsWith(".json")

  private def customBsps: List[BspOnly] = {
    val bspFolders =
      (workspace :: customProjectRoot.toList).distinct
        .map(_.resolve(Directories.bsp)) ++ bspGlobalDirectories
    val root = customProjectRoot.getOrElse(workspace)
    for {
      bspFolder <- bspFolders
      if (bspFolder.exists && bspFolder.isDirectory)
      buildTool <- bspFolder.toFile
        .listFiles()
        .flatMap(file =>
          if (file.isFile() && file.getName().endsWith(".json")) {
            val absolutePath = AbsolutePath(file.toPath())
            for {
              config <- BspServers.readInBspConfig(absolutePath, charset)
              if !knownBsps(config.getName())
            } yield BspOnly(
              config.getName(),
              root,
              absolutePath,
            )
          } else None
        )
        .toList
    } yield buildTool
  }

  private def knownBsps =
    Set(
      SbtBuildTool.name,
      MillBuildTool.bspName,
      BazelBuildTool.bspName,
    ) ++ ScalaCli.names

  private def customProjectRoot = userConfig().getCustomProjectRoot(workspace)

  private def searchForBuildTool(
      isProjectRoot: AbsolutePath => Boolean
  ): Option[AbsolutePath] = {
    customProjectRoot match {
      case Some(projectRoot) => Some(projectRoot).filter(isProjectRoot)
      case None =>
        if (isProjectRoot(workspace)) Some(workspace)
        else
          workspace.toNIO
            .toFile()
            .listFiles()
            .collectFirst {
              case file
                  if file.isDirectory &&
                    !file.getName.startsWith(".") &&
                    isProjectRoot(AbsolutePath(file.toPath())) =>
                AbsolutePath(file.toPath())
            }
    }
  }

  def allAvailable: List[BuildTool] = {
    List(
      SbtBuildTool(workspaceVersion = None, workspace, userConfig),
      GradleBuildTool(userConfig, workspace),
      MavenBuildTool(userConfig, workspace),
      MillBuildTool(userConfig, workspace),
      ScalaCliBuildTool(workspace, workspace, userConfig),
      BazelBuildTool(userConfig, workspace),
    )
  }

  def all: List[String] = {
    val buf = List.newBuilder[String]
    if (isBloop) buf += BloopServers.name
    if (isSbt) buf += "sbt"
    if (isMill) buf += "Mill"
    if (isGradle) buf += "Gradle"
    if (isMaven) buf += "Maven"
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
    bazelProject.foreach(buf += BazelBuildTool(userConfig, _))
    buf.addAll(customBsps)

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
    else if (bazelProject.exists(BazelBuildTool.isBazelRelatedPath(_, path)))
      Some(BazelBuildTool.name)
    else if (isInBsp(path)) {
      val name = path.filename.stripSuffix(".json")
      if (knownBsps(name) && !ScalaCli.names(name)) None
      else Some(name)
    } else None
  }

  def initialize(): Set[BuildTool] = {
    lastDetectedBuildTools
      .getAndSet(
        loadSupported().map(tool => tool.executableName -> tool).toMap
      )
      .values
      .toSet
  }

  def newBuildTool(buildTool: String): Boolean = {
    val newBuildTool = loadSupported().find(_.executableName == buildTool)
    newBuildTool match {
      case None => false
      case Some(newBuildTool) =>
        val before = lastDetectedBuildTools.getAndUpdate(
          _ + (newBuildTool.executableName -> newBuildTool)
        )
        !before.contains(newBuildTool.executableName)
    }
  }

  def current(): Set[BuildTool] = lastDetectedBuildTools.get().values.toSet

}

object BuildTools {
  def default(workspace: AbsolutePath = PathIO.workingDirectory): BuildTools =
    new BuildTools(
      workspace,
      Nil,
      () => UserConfiguration(),
      explicitChoiceMade = () => false,
      charset = StandardCharsets.UTF_8,
    )
}
