package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.semver.SemVer.isCompatibleVersion
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position

case class SbtBuildTool(
    workspaceVersion: Option[String],
    userConfig: () => UserConfiguration
) extends BuildTool
    with BloopInstallProvider
    with BuildServerProvider {

  import SbtBuildTool._

  /**
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because we
   * can't rely on `sbt` resolving correctly when using system processes, at
   * least it failed on Windows when I tried it.
   */
  lazy val embeddedSbtLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)
  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val sbtArgs = List[String](
      "-Dbloop.export-jar-classifiers=sources",
      "bloopInstall"
    )
    val allArgs = userConfig().sbtScript match {
      case Some(script) =>
        script :: sbtArgs
      case None =>
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Djline.terminal=jline.UnsupportedTerminal",
          "-Dsbt.log.noformat=true",
          "-Dfile.encoding=UTF-8"
        )
        val jarArgs = List(
          "-jar",
          embeddedSbtLauncher.toString()
        )
        val sbtVersion =
          if (workspaceVersion.isEmpty) List(s"-Dsbt.version=$version") else Nil
        List(
          javaArgs,
          sbtVersion,
          SbtOpts.fromWorkspace(workspace),
          JvmOpts.fromWorkspace(workspace),
          jarArgs,
          sbtArgs
        ).flatten
    }
    removeLegacyGlobalPlugin()
    writeBloopPlugin(workspace)
    allArgs
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(workspace)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = BuildInfo.sbtVersion

  override def createBspFileArgs(workspace: AbsolutePath): List[String] = {
    val sbtArgs = List[String](
      "bspConfig"
    )
    val allArgs = userConfig().sbtScript match {
      case Some(script) =>
        script :: sbtArgs
      case None =>
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Djline.terminal=jline.UnsupportedTerminal",
          "-Dsbt.log.noformat=true",
          "-Dfile.encoding=UTF-8"
        )
        val jarArgs = List(
          "-jar",
          embeddedSbtLauncher.toString()
        )
        List(
          javaArgs,
          SbtOpts.fromWorkspace(workspace),
          JvmOpts.fromWorkspace(workspace),
          jarArgs,
          sbtArgs
        ).flatten
    }
    allArgs
  }

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    loadVersion(workspace) match {
      case Some(version) =>
        scribe.info(s"sbt ${version} found for workspace.")
        val valid = isCompatibleVersion(firstVersionWithBsp, version)
        if (valid) {
          writeSingleSbtMetalsPlugin(
            workspace.resolve("project"),
            userConfig,
            isBloop = false
          )
        } else {
          scribe.warn(
            s"Unable to start sbt bsp server. Make sure you have sbt >= $firstVersionWithBsp defined in your build.properties file."
          )
        }
        valid
      case None =>
        scribe.warn("No sbt version can be found for sbt workspace.")
        false
    }
  }

  // We remove legacy metals.sbt file that was located in
  // global sbt plugins and which adds the plugin to each projects
  // and creates additional overhead.
  private def removeLegacyGlobalPlugin(): Unit = {
    def pluginsDirectory(version: String): AbsolutePath = {
      AbsolutePath(System.getProperty("user.home"))
        .resolve(".sbt")
        .resolve(version)
        .resolve("plugins")
    }
    val plugins =
      if (version.startsWith("0.13")) pluginsDirectory("0.13")
      else pluginsDirectory("1.0")

    val metalsFile = plugins.resolve("metals.sbt")
    Files.deleteIfExists(metalsFile.toNIO)
  }

  private def writeBloopPlugin(
      workspace: AbsolutePath
  ): Unit = {

    def sbtMetaDirs(
        meta: AbsolutePath,
        acc: Set[AbsolutePath]
    ): Set[AbsolutePath] = {
      if (meta.exists) {
        val files = meta.list.toList
        val hasSbtSrc = files.exists(f => f.isSbt && f.filename != "metals.sbt")
        if (hasSbtSrc) {
          val forSbtSupport = meta.resolve("project/project")
          sbtMetaDirs(meta.resolve("project"), acc + forSbtSupport)
        } else {
          acc
        }
      } else {
        acc
      }
    }

    val mainMeta = workspace.resolve("project")
    val metaMeta = workspace.resolve("project").resolve("project")
    sbtMetaDirs(mainMeta, Set(mainMeta, metaMeta)).foreach(dir =>
      writeSingleSbtMetalsPlugin(dir, userConfig, isBloop = true)
    )
  }

  override def toString: String = SbtBuildTool.name

  def executableName = SbtBuildTool.name
}

object SbtBuildTool {

  val name = "sbt"

  // Note: (ckipp01) first version was actually 1.4.0, but there is some issues
  // with bsp discovery with it. So we instead are going with 1.4.1 being the
  // first actually supported sbt version for bsp support in Metals.
  val firstVersionWithBsp = "1.4.1"

  /**
   * Based on whether sbt or Bloop is being used ensure that the correct
   * plugin is included in the workspace
   */
  def writeSingleSbtMetalsPlugin(
      projectDir: AbsolutePath,
      userConfig: () => UserConfiguration,
      isBloop: Boolean
  ): Unit = {
    if (isBloop && userConfig().bloopSbtAlreadyInstalled) {
      return
    } else {
      val versionToUse =
        if (isBloop) userConfig().currentBloopVersion
        else BuildInfo.metalsVersion

      val bytes =
        sbtPlugin(versionToUse, isBloop).getBytes(StandardCharsets.UTF_8)

      projectDir.toFile.mkdirs()
      val metalsPluginFile = projectDir.resolve("metals.sbt")
      val pluginFileShouldChange = !metalsPluginFile.isFile ||
        !metalsPluginFile.readAllBytes.sameElements(bytes)

      if (pluginFileShouldChange) {
        Files.write(metalsPluginFile.toNIO, bytes)
      }
    }
  }

  /**
   * Short description and artifact for the sbt-bloop plugin
   */
  private def bloopPluginDetails(version: String) =
    (
      "This file enables sbt-bloop to create bloop config files.",
      s""""ch.epfl.scala" % "sbt-bloop" % "$version""""
    )

  /**
   * Short description and artifact for the sbt-metals plugin
   */
  private def metalsPluginDetails(version: String) =
    (
      "This file enables semsantic information to be produced by sbt.",
      s""""org.scalameta" % "sbt-metals" % "$version""""
    )

  /**
   * Contents of metals.sbt file that is to be installed in the workspace.
   */
  private def sbtPlugin(version: String, isBloop: Boolean): String = {
    val isSnapshotVersion = version.contains("+")
    val resolvers = if (isSnapshotVersion) {
      """resolvers += Resolver.bintrayRepo("scalacenter", "releases")"""
    } else {
      ""
    }

    val (description, artifact) =
      if (isBloop) bloopPluginDetails(version)
      else metalsPluginDetails(version)

    s"""|// DO NOT EDIT! This file is auto-generated.
        |// $description
        |$resolvers
        |addSbtPlugin($artifact)
        |""".stripMargin
  }

  def isSbtRelatedPath(workspace: AbsolutePath, path: AbsolutePath): Boolean = {
    val project = workspace.toNIO.resolve("project")
    val isToplevel = Set(
      workspace.toNIO,
      project,
      project.resolve("project")
    )
    isToplevel(path.toNIO.getParent) && {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith("build.properties") ||
      filename.endsWith(".sbt") ||
      filename.endsWith(".scala")
    }
  }

  def apply(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration
  ): SbtBuildTool = {
    val version = loadVersion(workspace).map(_.toString())
    SbtBuildTool(version, userConfig)
  }

  def loadVersion(workspace: AbsolutePath): Option[String] = {
    val props = new Properties()
    val buildproperties =
      workspace.resolve("project").resolve("build.properties")

    if (!buildproperties.isFile) None
    else {
      val in = Files.newInputStream(buildproperties.toNIO)
      try props.load(in)
      finally in.close()
      Option(props.getProperty("sbt.version"))
    }
  }

  def sbtInputPosAdjustment(
      originInput: Input.VirtualFile,
      autoImports: Seq[String],
      uri: String,
      position: Position
  ): (Input.VirtualFile, Position, AdjustLspData) = {

    val appendStr = autoImports.mkString("", "\n", "\n")
    val appendLineSize = autoImports.size

    val modifiedInput =
      originInput.copy(value = appendStr + originInput.value)
    val pos = new Position(
      appendLineSize + position.getLine(),
      position.getCharacter()
    )
    val adjustLspData = AdjustedLspData.create(
      pos => {
        new Position(pos.getLine() - appendLineSize, pos.getCharacter())
      },
      filterOutLocations = { loc => !loc.getUri().isSbt }
    )
    (modifiedInput, pos, adjustLspData)
  }
}
