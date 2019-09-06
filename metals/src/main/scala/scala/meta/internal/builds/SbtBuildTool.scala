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
      "metalsEnable",
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
    writeSbtMetalsPlugin(config)
    allArgs
  }
  override def digest(
      workspace: AbsolutePath
  ): Option[String] = SbtDigest.current(workspace)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = "1.3.0"

  private def writeSbtMetalsPlugin(config: MetalsServerConfig): Unit = {
    val plugins =
      if (version.startsWith("0.13")) SbtBuildTool.pluginsDirectory("0.13")
      else SbtBuildTool.pluginsDirectory("1.0")
    plugins.createDirectories()
    val bytes = SbtBuildTool
      .globalMetalsSbt(config.bloopSbtVersion)
      .getBytes(StandardCharsets.UTF_8)
    val destination = plugins.resolve("metals.sbt")
    if (destination.isFile && destination.readAllBytes.sameElements(bytes)) {
      // Do nothing if the file is unchanged. If we write to the file unconditionally
      // we risk triggering sbt re-compilation of global plugins that slows down
      // build import greatly. If somebody validates it doesn't affect load times
      // then feel free to remove this guard.
      ()
    } else {
      Files.write(destination.toNIO, bytes)
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

  def pluginsDirectory(version: String): AbsolutePath = {
    AbsolutePath(System.getProperty("user.home"))
      .resolve(".sbt")
      .resolve(version)
      .resolve("plugins")
  }

  /**
   * Contents of metals.sbt file that is installed globally.
   */
  private def globalMetalsSbt(bloopSbtVersion: String): String = {
    val resolvers =
      if (BuildInfo.metalsVersion.endsWith("-SNAPSHOT")) {
        """|resolvers ++= {
           |  if (System.getenv("METALS_ENABLED") == "true") {
           |    List(Resolver.sonatypeRepo("snapshots"))
           |  } else {
           |    List()
           |  }
           |}
           |""".stripMargin
      } else {
        ""
      }
    s"""|// DO NOT EDIT! This file is auto-generated.
        |// By default, this file does not do anything.
        |// If the environment variable METALS_ENABLED has the value 'true',
        |// then this file enables sbt-metals and sbt-bloop.
        |$resolvers
        |libraryDependencies := {
        |  import Defaults.sbtPluginExtra
        |  val oldDependencies = libraryDependencies.value
        |  if (System.getenv("METALS_ENABLED") == "true") {
        |    val bloopModule = "ch.epfl.scala" % "sbt-bloop" % "$bloopSbtVersion"
        |    val metalsModule = "org.scalameta" % "sbt-metals" % "${BuildInfo.metalsVersion}"
        |    val sbtVersion = Keys.sbtBinaryVersion.in(TaskKey[Unit]("pluginCrossBuild")).value
        |    val scalaVersion = Keys.scalaBinaryVersion.in(update).value
        |    val bloopPlugin = sbtPluginExtra(bloopModule, sbtVersion, scalaVersion)
        |    val metalsPlugin = sbtPluginExtra(metalsModule, sbtVersion, scalaVersion)
        |    List(bloopPlugin, metalsPlugin) ++ oldDependencies.filterNot { dep =>
        |      (dep.organization == "ch.epfl.scala" && dep.name == "sbt-bloop") ||
        |      (dep.organization == "org.scalameta" && dep.name == "sbt-metals")
        |    }
        |  } else {
        |    oldDependencies
        |  }
        |}
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
