package scala.meta.internal.bsp

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.bsp.BspConfigGenerationStatus._
import scala.meta.internal.builds.BuildServerProvider
import scala.meta.internal.builds.BuildTool
import scala.meta.internal.builds.BuildTools
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.Messages.BspProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.MessageActionItem

/**
 * Runs a process to create a .bsp entry for a givev buildtool.
 */
final class BspConfigGenerator(
    workspace: AbsolutePath,
    languageClient: MetalsLanguageClient,
    buildTools: BuildTools,
    shellRunner: ShellRunner
)(implicit ec: ExecutionContext) {
  def runUnconditionally(
      buildTool: BuildTool,
      args: List[String]
  ): Future[BspConfigGenerationStatus] =
    shellRunner
      .run(
        s"${buildTool.executableName} bspConfig",
        args,
        workspace,
        buildTool.redirectErrorOutput
      )
      .map(BspConfigGenerationStatus.fromExitCode)

  /**
   * Given multiple build tools that are all BuildServerProviders, allow the
   * choose the desired build server and then connect to it.
   */
  def chooseAndGenerate(
      buildTools: List[BuildServerProvider]
  ): Future[(BuildTool, BspConfigGenerationStatus)] = {
    for {
      Some(buildTool) <- chooseBuildServerProvider(buildTools)
      status <- buildTool.generateBspConfig(
        workspace,
        args => runUnconditionally(buildTool, args)
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
          new MessageActionItem(buildTool.executableName) == choice
        )
      }
  }
}
