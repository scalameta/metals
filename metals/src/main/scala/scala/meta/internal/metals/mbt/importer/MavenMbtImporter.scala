package scala.meta.internal.metals.mbt.importer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

abstract class MavenMbtImporter(
    val projectRoot: AbsolutePath,
    shellRunner: ShellRunner,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext)
    extends MbtImportProvider {

  def mavenBaseCommand(): List[String]

  protected def pluginVersion: String = BuildInfo.metalsMavenPluginVersion

  override val name: String = "maven"

  private def pluginCoordinates: String =
    s"org.scalameta:metals-maven-plugin:$pluginVersion:export"

  protected def runExport(args: List[String]): Future[Int] =
    shellRunner
      .run(
        "maven-export",
        args,
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
      )
      .future

  override def extract(workspace: AbsolutePath): Future[Unit] = {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)
    val baseArgs = mavenBaseCommand()
    val snapshotSettings =
      if (
        MavenMbtImporter.needsSnapshotGlobalSettings(
          pluginVersion,
          baseArgs,
          projectRoot,
        )
      )
        Some(MavenMbtImporter.writeSnapshotGlobalSettings())
      else None
    val settingsArgs = snapshotSettings match {
      case Some(settingsPath) =>
        List("--global-settings", settingsPath.toString)
      case None => Nil
    }
    val args = baseArgs ::: settingsArgs ::: List(
      "--quiet",
      pluginCoordinates,
      s"-DmbtOutputFile=${out}",
    )
    val exportFuture =
      try runExport(args)
      catch {
        case NonFatal(e) => Future.failed(e)
      }
    exportFuture
      .andThen { case _ =>
        snapshotSettings.foreach(path => Try(Files.deleteIfExists(path)))
      }
      .flatMap { code =>
        if (code == ExitCodes.Cancel)
          Future.failed(
            new java.util.concurrent.CancellationException(
              "mbt-maven-export was cancelled"
            )
          )
        else if (code == ExitCodes.Success && Files.exists(out.toNIO))
          Future.successful(())
        else
          Future.failed(
            new Exception(
              s"mbt-maven-export failed with exit code $code"
            )
          )
      }
  }

}

object MavenMbtImporter {

  val centralSnapshotsUrl: String =
    "https://central.sonatype.com/repository/maven-snapshots/"

  val snapshotPluginSettingsXml: String =
    s"""|<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
        |          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        |          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">
        |  <profiles>
        |    <profile>
        |      <id>metals-snapshots</id>
        |      <pluginRepositories>
        |        <pluginRepository>
        |          <id>central-snapshots</id>
        |          <url>$centralSnapshotsUrl</url>
        |          <releases>
        |            <enabled>false</enabled>
        |          </releases>
        |          <snapshots>
        |            <enabled>true</enabled>
        |          </snapshots>
        |        </pluginRepository>
        |      </pluginRepositories>
        |    </profile>
        |  </profiles>
        |  <activeProfiles>
        |    <activeProfile>metals-snapshots</activeProfile>
        |  </activeProfiles>
        |</settings>
        |""".stripMargin

  def needsSnapshotGlobalSettings(
      pluginVersion: String,
      baseArgs: List[String],
      projectRoot: AbsolutePath,
  ): Boolean =
    pluginVersion.endsWith("-SNAPSHOT") &&
      !baseArgs.exists(isGlobalSettingsArg) &&
      !hasProjectGlobalSettings(projectRoot)

  private[importer] def writeSnapshotGlobalSettings(): Path = {
    val file = Files.createTempFile("metals-maven-snapshots-", ".xml")
    try {
      Files.writeString(file, snapshotPluginSettingsXml, StandardCharsets.UTF_8)
      file
    } catch {
      case NonFatal(e) =>
        Try(Files.deleteIfExists(file))
        throw e
    }
  }

  private def isGlobalSettingsArg(arg: String): Boolean =
    arg == "-gs" ||
      arg.startsWith("-gs=") ||
      arg == "--global-settings" ||
      arg.startsWith("--global-settings=")

  private def hasProjectGlobalSettings(projectRoot: AbsolutePath): Boolean = {
    val mavenConfig = projectRoot.resolve(".mvn/maven.config").toNIO
    if (!Files.isRegularFile(mavenConfig)) false
    else {
      val tokens = for {
        rawLine <- Try(
          Files.readString(mavenConfig, StandardCharsets.UTF_8).split("\\R")
        ).getOrElse(Array.empty[String])
        line = rawLine.trim
        if line.nonEmpty && !line.startsWith("#")
        token <- line.split("\\s+")
      } yield token
      tokens.exists(isGlobalSettingsArg)
    }
  }

}
