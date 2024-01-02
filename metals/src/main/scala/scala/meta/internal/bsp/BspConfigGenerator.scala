package scala.meta.internal.bsp

import java.nio.file.Files
import java.nio.file.StandardCopyOption

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Messages.BspProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem

/**
 * Runs a process to create a .bsp entry for a given buildtool.
 */
final class BspConfigGenerator(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    shellRunner: ShellRunner,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration,
)(implicit ec: ExecutionContext) {
  def runUnconditionally(
      buildTool: BuildServerProvider,
      args: List[String],
  ): Future[BspConfigGenerationStatus] =
    shellRunner
      .run(
        s"${buildTool.buildServerName} bspConfig",
        args,
        buildTool.projectRoot,
        buildTool.redirectErrorOutput,
        userConfig().javaHome,
      )
      .map(BspConfigGenerationStatus.fromExitCode)
      .map {
        case Generated if buildTool.projectRoot != workspace =>
          try {
            val bsp = ".bsp"
            workspace.resolve(bsp).createDirectories()
            val buildToolBspDir = buildTool.projectRoot.resolve(bsp)
            val workspaceBspDir = workspace.resolve(bsp).toNIO
            buildToolBspDir.toFile.listFiles().foreach { file =>
              val path = file.toPath()
              if (!file.isDirectory() && path.filename.endsWith(".json")) {
                val to = workspaceBspDir.resolve(path.filename)
                Files.move(path, to, StandardCopyOption.REPLACE_EXISTING)
              }
            }
            buildToolBspDir.deleteRecursively()
            Generated
          } catch {
            case NonFatal(e) =>
              val message = s"Could not move bsp config from project root: $e"
              scribe.error(message)
              Failed(Right(message))
          }
        case status => status
      }

  /**
   * Given multiple build tools that are all BuildServerProviders, allow the
   * choose the desired build server and then connect to it.
   */
  def chooseAndGenerate(
      buildTools: List[BuildServerProvider]
  ): Future[(BuildServerProvider, BspConfigGenerationStatus)] = {
    for {
      Some(buildTool) <- chooseBuildServerProvider(buildTools)
      status <- buildTool.generateBspConfig(
        workspace,
        args => runUnconditionally(buildTool, args),
        statusBar,
      )
    } yield (buildTool, status)
  }

  private def chooseBuildServerProvider(
      buildTools: List[BuildServerProvider]
  ): Future[Option[BuildServerProvider]] = {
    languageClient
      .showMessageRequest(BspProvider.params(buildTools))
      .asScala
      .map { choice =>
        buildTools.find(buildTool =>
          new MessageActionItem(buildTool.buildServerName) == choice
        )
      }
  }
}
