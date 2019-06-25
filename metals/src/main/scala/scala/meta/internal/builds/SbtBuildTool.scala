package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsEnrichments._

case class SbtBuildTool(version: String) extends BuildTool {

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

  override def args(
      workspace: AbsolutePath,
      userConfig: () => UserConfiguration,
      config: MetalsServerConfig
  ): List[String] = {
    val sbtArgs = List[String](
      "set bloopExportJarClassifiers := Some(Set(\"source\"))",
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
    writeSbtMetalsPlugin(workspace, config)
    allArgs
  }

  override def digest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = "1.2.8"

  private def writeSbtMetalsPlugin(
      workspace: AbsolutePath,
      config: MetalsServerConfig
  ): Unit = {
    val bytes = SbtBuildTool
      .sbtPlugin(config.bloopSbtVersion)
      .getBytes(StandardCharsets.UTF_8)
    val projectDir = workspace.resolve("project")
    projectDir.toFile.mkdir()
    val metalsPluginfile = projectDir.resolve("metals.sbt")
    if (metalsPluginfile.isFile && metalsPluginfile.readAllBytes.sameElements(
        bytes
      )) {
      // Do nothing if the file is unchanged. If we write to the file unconditionally
      // we risk triggering sbt re-compilation of global plugins that slows down
      // build import greatly. If somebody validates it doesn't affect load times
      // then feel free to remove this guard.
      ()
    } else {
      Files.write(metalsPluginfile.toNIO, bytes)
    }
    val gitignore = workspace.resolve(".gitignore")
    val gitIgnoreContents = "project/metals.sbt"
    if (gitignore.exists && !gitignore.readText.contains(gitIgnoreContents)) {
      gitignore.writeText(s"\n$gitIgnoreContents\n")
    }
  }

  override def toString: String = "sbt"

  def executableName = "sbt"
}

object SbtBuildTool {

  def isSbtRelatedPath(workspace: AbsolutePath, path: AbsolutePath) = {
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
    val resolvers = if (bloopSbtVersion.contains("+")) {
      """resolvers += Resolver.bintrayRepo("scalacenter", "releases")"""
    } else {
      ""
    }
    s"""|// DO NOT EDIT! This file is auto-generated.
        |// This file enables sbt-bloop to created bloop config files.
        |$resolvers
        |addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "$bloopSbtVersion")
        |""".stripMargin
  }

  def apply(workspace: AbsolutePath): SbtBuildTool = {
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
    SbtBuildTool(version.getOrElse(unknown))
  }

  private def unknown = "<unknown>"
}
