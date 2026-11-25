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

  override val name: String = "maven"

  protected def pluginVersion: String = BuildInfo.metalsMavenPluginVersion

  /**
   * Builds the export command. SNAPSHOT plugin versions are only published to
   * the Central snapshots repository, so we pass a temporary global settings
   * file that adds it as a plugin repository. Maven still merges the user's
   * `~/.m2/settings.xml` (or `-s` from `.mvn/maven.config`) on top of it.
   */
  private[importer] def prepareExportCommand(
      out: AbsolutePath
  ): (List[String], Option[Path]) = {
    val outDir = out.toNIO.getParent
    Files.createDirectories(outDir)
    val snapshotSettings =
      if (pluginVersion.endsWith("-SNAPSHOT")) {
        val file = Files.createTempFile(
          outDir,
          "metals-maven-snapshot-settings-",
          ".xml",
        )
        try {
          Files.write(
            file,
            MavenMbtImporter.snapshotPluginSettingsXml.getBytes(
              StandardCharsets.UTF_8
            ),
          )
          Some(file)
        } catch {
          case NonFatal(e) =>
            Files.deleteIfExists(file)
            throw e
        }
      } else None
    val settingsArgs =
      snapshotSettings.toList.flatMap(f =>
        List("--global-settings", f.toString)
      )
    val args = mavenBaseCommand() ::: settingsArgs :::
      List(
        "--quiet",
        s"org.scalameta:metals-maven-plugin:$pluginVersion:export",
        s"-DmbtOutputFile=$out",
      )
    (args, snapshotSettings)
  }

  override def extract(workspace: AbsolutePath): Future[Unit] = {
    val out = outputPath(workspace)
    Future.fromTry(Try(prepareExportCommand(out))).flatMap {
      case (args, snapshotSettings) =>
        Future
          .fromTry(Try {
            shellRunner
              .run(
                "maven-export",
                args,
                projectRoot,
                redirectErrorOutput = false,
                javaHome = userConfig().javaHome,
              )
              .future
          })
          .flatten
          .andThen { case _ => snapshotSettings.foreach(Files.deleteIfExists) }
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

}

object MavenMbtImporter {

  val centralSnapshotsUrl: String =
    "https://central.sonatype.com/repository/maven-snapshots/"

  // Specific enough not to clash with a profile in the user's settings.xml,
  // which Maven would otherwise prefer over ours.
  val snapshotsProfileId: String = "metals-mbt-central-snapshots-plugin-repo"

  val snapshotPluginSettingsXml: String =
    s"""|<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
        |  <profiles>
        |    <profile>
        |      <id>$snapshotsProfileId</id>
        |      <pluginRepositories>
        |        <pluginRepository>
        |          <id>metals-mbt-central-snapshots</id>
        |          <url>$centralSnapshotsUrl</url>
        |          <releases><enabled>false</enabled></releases>
        |          <snapshots><enabled>true</enabled></snapshots>
        |        </pluginRepository>
        |      </pluginRepositories>
        |    </profile>
        |  </profiles>
        |  <activeProfiles>
        |    <activeProfile>$snapshotsProfileId</activeProfile>
        |  </activeProfiles>
        |</settings>
        |""".stripMargin
}
