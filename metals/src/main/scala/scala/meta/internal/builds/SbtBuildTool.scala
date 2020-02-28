package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath

case class SbtBuildTool(
    version: String,
    userConfig: () => UserConfiguration,
    config: MetalsServerConfig
) extends BloopPluginBuildTool {

  /**
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because
   * we can't rely on `sbt` resolving correctly when using system processes, at least
   * it failed on Windows when I tried it.
   */
  lazy val embeddedSbtLauncher: AbsolutePath = {
    val out = BuildTool.copyFromResource(tempDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  override def args(workspace: AbsolutePath): List[String] = {
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
        List(
          javaArgs,
          SbtOpts.fromWorkspace(workspace),
          JvmOpts.fromWorkspace(workspace),
          jarArgs,
          sbtArgs
        ).flatten
    }
    removeLegacyGlobalPlugin()
    writeSbtMetalsPlugin(workspace, config)
    allArgs
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(workspace)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = "1.2.8"

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

  private def writeSbtMetalsPlugin(
      workspace: AbsolutePath,
      config: MetalsServerConfig
  ): Unit = {
    if (userConfig().bloopSbtAlreadyInstalled) return
    val versionToUse = userConfig().currentBloopVersion
    val bytes = SbtBuildTool
      .sbtPlugin(versionToUse)
      .getBytes(StandardCharsets.UTF_8)
    val projectDir = workspace.resolve("project")
    projectDir.toFile.mkdir()
    val metalsPluginFile = projectDir.resolve("metals.sbt")
    val pluginFileShouldChange = !metalsPluginFile.isFile ||
      !metalsPluginFile.readAllBytes.sameElements(bytes)
    if (pluginFileShouldChange) {
      Files.write(metalsPluginFile.toNIO, bytes)
    }
  }

  override def toString: String = "sbt"

  def executableName = "sbt"
}

object SbtBuildTool {

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

  /**
   * Contents of metals.sbt file that is installed in the workspace.
   */
  private def sbtPlugin(bloopSbtVersion: String): String = {
    val isSnapshotVersion = bloopSbtVersion.contains("+")
    val resolvers = if (isSnapshotVersion) {
      """resolvers += Resolver.bintrayRepo("scalacenter", "releases")"""
    } else {
      ""
    }
    s"""|// DO NOT EDIT! This file is auto-generated.
        |// This file enables sbt-bloop to create bloop config files.
        |$resolvers
        |addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$bloopSbtVersion")
        |""".stripMargin
  }

  def apply(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): SbtBuildTool = {
    val props = new Properties()
    val buildproperties =
      workspace.resolve("project").resolve("build.properties")
    val version =
      if (!buildproperties.isFile) None
      else {
        val in = Files.newInputStream(buildproperties.toNIO)
        try props.load(in)
        finally in.close()
        Option(props.getProperty("sbt.version"))
      }
    SbtBuildTool(version.getOrElse(unknown), userConfig, config)
  }

  private def unknown = "<unknown>"
}
