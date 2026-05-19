package scala.meta.internal.metals.mbt.importer

import java.nio.file.Files

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.UserConfiguration
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

  private val pluginCoordinates =
    s"org.scalameta:metals-maven-plugin:${BuildInfo.metalsMavenPluginVersion}:export"

  override def extract(workspace: AbsolutePath): Future[Unit] = {
    val out = outputPath(workspace)
    Files.createDirectories(out.toNIO.getParent)
    val args = mavenBaseCommand() :::
      List(
        "--quiet",
        pluginCoordinates,
        s"-DmbtOutputFile=${out}",
      )
    shellRunner
      .run(
        "maven-export",
        args,
        projectRoot,
        redirectErrorOutput = false,
        javaHome = userConfig().javaHome,
      )
      .future
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
