package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.semver.SemVer
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
  def embeddedSbtLauncher(
      outDir: Path
  ): AbsolutePath = {
    val out = BuildTool.copyFromResource(outDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)
  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val bloopInstallArgs = List[String](
      "-Dbloop.export-jar-classifiers=sources",
      "bloopInstall"
    )
    val allArgs = composeArgs(bloopInstallArgs, workspace, tempDir)
    removeLegacyGlobalPlugin()
    writeBloopPlugin(workspace)
    allArgs
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(workspace)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = BuildInfo.sbtVersion

  override def createBspFileArgs(workspace: AbsolutePath): List[String] = {
    val bspConfigArgs = List[String](
      "bspConfig"
    )
    val bspDir = workspace.resolve(".bsp").toNIO
    composeArgs(bspConfigArgs, workspace, bspDir)
  }

  private def composeArgs(
      sbtArgs: List[String],
      workspace: AbsolutePath,
      sbtLauncherOutDir: Path
  ): List[String] = {
    userConfig().sbtScript match {
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
          embeddedSbtLauncher(sbtLauncherOutDir).toString()
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
  }

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    loadVersion(workspace) match {
      case Some(version) =>
        scribe.info(s"sbt ${version} found for workspace.")
        val valid = isCompatibleVersion(firstVersionWithBsp, version)
        if (valid) {
          writeSbtMetalsPlugins(workspace)
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

    if (!userConfig().bloopSbtAlreadyInstalled) {
      val pluginVersion =
        // from 1.4.6 Bloop is not compatible with sbt < 1.3.0
        if (SemVer.isLaterVersion(version, "1.3.0"))
          "1.4.6"
        else "1.4.11"

      val plugin = bloopPluginDetails(pluginVersion)
      val mainMeta = workspace.resolve("project")
      val metaMeta = workspace.resolve("project").resolve("project")
      sbtMetaDirs(mainMeta, Set(mainMeta, metaMeta)).foreach(dir =>
        writePlugins(dir, plugin)
      )
    }
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
   * Write the sbt plugin in the sbt project directory
   */
  def writePlugins(
      projectDir: AbsolutePath,
      plugins: PluginDetails*
  ): Unit = {
    val content =
      s"""|// DO NOT EDIT! This file is auto-generated.
          |
          |${plugins.map(sbtPlugin).mkString("\n")}
          |""".stripMargin
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    projectDir.toFile.mkdirs()
    val metalsPluginFile = projectDir.resolve("metals.sbt")
    val pluginFileShouldChange = !metalsPluginFile.isFile ||
      !metalsPluginFile.readAllBytes.sameElements(bytes)

    if (pluginFileShouldChange) {
      Files.write(metalsPluginFile.toNIO, bytes)
    }
  }

  /**
   * Write all the plugins used by Metals when connected to sbt server:
   * - the sbt-metals plugin in the project directory
   * - the sbt-jdi-tools plugin in the project/project directory
   */
  def writeSbtMetalsPlugins(workspace: AbsolutePath): Unit = {
    val mainMeta = workspace.resolve("project")
    val metaMeta = workspace.resolve("project").resolve("project")
    writePlugins(mainMeta, metalsPluginDetails, debugAdapterPluginDetails)
    writePlugins(metaMeta, metalsPluginDetails, jdiToolsPluginDetails)
  }

  private case class PluginDetails private (
      description: Seq[String],
      artifact: String,
      resolver: Option[String]
  )

  /**
   * Short description and artifact for the sbt-bloop plugin
   */
  private def bloopPluginDetails(version: String): PluginDetails = {
    val resolver =
      if (isSnapshotVersion(version))
        Some("""Resolver.bintrayRepo("scalacenter", "releases")""")
      else None

    PluginDetails(
      description =
        Seq("This file enables sbt-bloop to create bloop config files."),
      artifact = s""""ch.epfl.scala" % "sbt-bloop" % "$version"""",
      resolver
    )
  }

  /**
   * Short description and artifact for the sbt-metals plugin
   */
  private def metalsPluginDetails: PluginDetails = {
    val resolver =
      if (isSnapshotVersion(BuildInfo.metalsVersion))
        Some(
          """"Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots""""
        )
      else None

    PluginDetails(
      Seq(
        "This plugin enables semantic information to be produced by sbt.",
        "It also adds support for debugging using the Debug Adapter Protocol"
      ),
      s""""org.scalameta" % "sbt-metals" % "${BuildInfo.metalsVersion}"""",
      resolver
    )
  }

  /**
   * The sbt-debug-adpater plugin needs sbt-jdi-tool in its meta-build
   * (in project/project/metals.sbt)
   */
  private def debugAdapterPluginDetails: PluginDetails =
    PluginDetails(
      Seq(
        "This plugin adds the BSP debug capability to sbt server."
      ),
      s""""ch.epfl.scala" % "sbt-debug-adapter" % "${BuildInfo.debugAdapterVersion}"""",
      resolver = None
    )

  /**
   * Short description and artifact for the sbt-jdi-tools plugin
   */
  private def jdiToolsPluginDetails: PluginDetails =
    PluginDetails(
      Seq(
        "This plugin makes sure that the JDI tools are in the sbt classpath.",
        "JDI tools are used by the debug adapter server."
      ),
      s""""org.scala-debugger" % "sbt-jdi-tools" % "${BuildInfo.sbtJdiToolsVersion}"""",
      resolver = None
    )

  private def isSnapshotVersion(version: String): Boolean =
    version.contains("+")

  /**
   * Contents of metals.sbt file that is to be installed in the workspace.
   */
  private def sbtPlugin(plugin: PluginDetails): String = {
    val resolvers = plugin.resolver.map(r => s"resolvers += $r").getOrElse("")
    val description = plugin.description.mkString("// ", "\n// ", "")

    s"""|$description
        |$resolvers
        |addSbtPlugin(${plugin.artifact})
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
      uri: String
  ): (Input.VirtualFile, Position => Position, AdjustLspData) = {

    val appendLineSize = autoImports.size

    val modifiedInput =
      originInput.copy(value =
        prependAutoImports(originInput.value, autoImports)
      )
    def adjustRequest(position: Position) = new Position(
      appendLineSize + position.getLine(),
      position.getCharacter()
    )
    val adjustLspData = AdjustedLspData.create(
      pos => {
        new Position(pos.getLine() - appendLineSize, pos.getCharacter())
      },
      filterOutLocations = { loc => !loc.getUri().isSbt }
    )
    (modifiedInput, adjustRequest, adjustLspData)
  }

  def prependAutoImports(text: String, autoImports: Seq[String]): String = {
    val prepend = autoImports.mkString("", "\n", "\n")
    prepend + text
  }
}
