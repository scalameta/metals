package tests

import ch.epfl.scala.bsp4j.BuildServer
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import org.eclipse.lsp4j
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.meta.internal.metals.BloopServers
import scala.meta.internal.metals.Embedded
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.MetalsSlowTaskParams
import scala.meta.internal.metals.MetalsSlowTaskResult
import scala.meta.internal.metals.MetalsStatusParams
import scala.meta.internal.metals.ProgressTicks
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.io.AbsolutePath

object BspCli {

  /**
   * Basic command-line tool to manually test `BloopServers` in isolation.
   *
   * Usage from sbt shell: {{{
   *   metals/runMain scala.meta.internal.metals.BloopServers ../test-workspace compile crossJVM
   * }}}
   */
  def main(args: Array[String]): Unit = {
    args.toList match {
      case directory :: "compile" :: targets =>
        val sh = Executors.newSingleThreadScheduledExecutor()
        val ex = Executors.newCachedThreadPool()
        implicit val ec = ExecutionContext.fromExecutorService(ex)
        val workspace = AbsolutePath(directory)
        val config = MetalsServerConfig.default
        val icons = Icons.none
        val time = Time.system
        val statusBar: StatusBar =
          new StatusBar(() => loggingLangaugeClient, time, ProgressTicks.none)
        val embedded = new Embedded(icons, statusBar, () => UserConfiguration())
        val server = new BloopServers(
          sh,
          workspace,
          loggingBuildClient,
          config,
          icons,
          embedded,
          statusBar
        )
        try {
          val future = compile(server, targets)
          Await.result(future, Duration("1min"))
        } finally {
          sh.shutdown()
          ex.shutdown()
        }
        println("goodbye!")
      case els =>
        System.err.println(
          s"expected '<workspace> compile [..targets]'. obtained $els"
        )
        System.exit(1)
    }
  }
  private def loggingLangaugeClient: MetalsLanguageClient =
    new MetalsLanguageClient {
      override def metalsExecuteClientCommand(
          params: ExecuteCommandParams
      ): Unit = {}
      override def metalsStatus(params: MetalsStatusParams): Unit = ()
      override def metalsSlowTask(
          params: MetalsSlowTaskParams
      ): CompletableFuture[MetalsSlowTaskResult] =
        new CompletableFuture[MetalsSlowTaskResult]()
      override def telemetryEvent(`object`: Any): Unit = ()
      override def publishDiagnostics(
          diagnostics: lsp4j.PublishDiagnosticsParams
      ): Unit = ()
      override def showMessage(messageParams: MessageParams): Unit = ()
      override def showMessageRequest(
          requestParams: ShowMessageRequestParams
      ): CompletableFuture[MessageActionItem] =
        new CompletableFuture[MessageActionItem]()
      override def logMessage(message: MessageParams): Unit = ()
    }
  private def loggingBuildClient: MetalsBuildClient = new MetalsBuildClient {
    override def onBuildShowMessage(params: MessageParams): Unit =
      pprint.log(params)
    override def onBuildLogMessage(
        params: MessageParams
    ): Unit = pprint.log(params)
    override def onBuildPublishDiagnostics(
        params: PublishDiagnosticsParams
    ): Unit = pprint.log(params)
    override def onBuildTargetDidChange(
        params: DidChangeBuildTarget
    ): Unit = pprint.log(params)
    override def onBuildTargetCompileReport(params: CompileReport): Unit =
      pprint.log(params)
    override def onConnect(remoteServer: BuildServer): Unit =
      pprint.log(remoteServer)
  }

  private def compile(
      bloopServers: BloopServers,
      targets: List[String]
  )(implicit ec: ExecutionContextExecutorService): Future[Unit] = {
    for {
      bloop <- bloopServers.newServer().map(_.get)
      buildTargets <- bloop.server.workspaceBuildTargets().asScala
      ids = buildTargets.getTargets.asScala
        .filter(target => targets.contains(target.getDisplayName))
      names = ids.map(_.getDisplayName).mkString(" ")
      _ = scribe.info(s"compiling: $names")
      params = new CompileParams(ids.map(_.getId).asJava)
      _ <- bloop.server.buildTargetCompile(params).asScala
      _ <- bloop.shutdown()
    } yield {
      scribe.info("done!")
    }
  }

}
