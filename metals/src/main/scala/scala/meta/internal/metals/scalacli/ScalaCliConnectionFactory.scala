package scala.meta.internal.metals.scalacli

import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.internal.metals.BuildServerConnectionFactory
import scala.meta.internal.metals.Cancelable
import scala.meta.internal.metals.ClosableOutputStream
import scala.meta.internal.metals.DismissedNotifications
import scala.meta.internal.metals.MetalsBuildClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.SocketConnection
import scala.meta.internal.metals.WorkDoneProgress
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.process.SystemProcess
import scala.meta.io.AbsolutePath

abstract class ScalaCliConnectionFactory(
    workspace: AbsolutePath,
    client: MetalsBuildClient,
    languageClient: ConfiguredLanguageClient,
    requestTimeOutNotification: DismissedNotifications#Notification,
    reconnectNotification: DismissedNotifications#Notification,
    config: MetalsServerConfig,
    workDoneProgress: WorkDoneProgress,
    launchCommand: Seq[String],
) extends BuildServerConnectionFactory(
      projectRoot = workspace,
      bspTraceRoot = workspace,
      client,
      languageClient,
      requestTimeOutNotification,
      reconnectNotification,
      config,
      serverName = "Scala CLI",
      bspStatusOpt = None,
      supportsWrappedSources = Some(true),
      workDoneProgress = workDoneProgress,
    ) {

  override protected def connect()(implicit
      ec: ExecutionContextExecutorService
  ): Future[SocketConnection] = Future {
    scribe.info(s"Running $launchCommand")
    val proc = SystemProcess.run(
      launchCommand.toList,
      workspace,
      redirectErrorOutput = false,
      env = Map("SCALA_CLI_POWER" -> "true"),
      processOut = None,
      processErr = Some(line => scribe.info("Scala CLI: " + line)),
      discardInput = false,
      threadNamePrefix = "scala-cli",
    )
    val finished = Promise[Unit]()
    proc.complete.ignoreValue.onComplete { res =>
      finished.tryComplete(res)
    }
    SocketConnection(
      ScalaCli.names.head,
      new ClosableOutputStream(proc.outputStream, "Scala CLI error stream"),
      proc.inputStream,
      List(Cancelable { () => proc.cancel }),
      finished,
    )
  }

}
