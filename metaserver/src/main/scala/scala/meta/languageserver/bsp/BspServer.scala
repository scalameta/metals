package scala.meta.languageserver.bsp

import ch.epfl.`scala`.bsp.schema.{BuildClientCapabilities, InitializeBuildParams, InitializedBuildParams}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import org.langmeta.io.AbsolutePath
import org.langmeta.jsonrpc.{BaseProtocolMessage, JsonRpcClient, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer, TextDocument, Window}
import ch.epfl.scala.bsp.endpoints

case class BspServer(
    client: JsonRpcClient,
    runningServer: CancelableFuture[Unit]
)

object BspServer {
  def connect(
      cwd: AbsolutePath,
      services: Services,
      cmd: String,
      logger: Logger
  )(
      implicit scheduler: Scheduler
  ): Task[Either[String, BspServer]] = {
    val bspExecution = new ProcessBuilder(cmd).start()
    val bspOut = bspExecution.getOutputStream()
    val bspIn = bspExecution.getInputStream()

    implicit val client: LanguageClient = new LanguageClient(bspOut, logger)
    val messages = BaseProtocolMessage.fromInputStream(bspIn)
    val server =
      new LanguageServer(messages, client, services, scheduler, logger)
    val runningServer =
      server.startTask.doOnCancel(Task.eval(bspExecution.destroy())).runAsync
    val initialization = endpoints.Build.initialize.request(
      InitializeBuildParams(
        rootUri = cwd.syntax,
        Some(BuildClientCapabilities(List("scala")))
      )
    )

    initialization.map { _ =>
      endpoints.Build.initialized.notify(InitializedBuildParams())
      Right(BspServer(client, runningServer))
    }
  }

  def forwardingServices(editorClient: JsonRpcClient): Services = {
    Services.empty
      .notification(Window.logMessage) { msg =>
        editorClient.notify(Window.logMessage.method, msg)
      }
      .notification(TextDocument.publishDiagnostics) { msg =>
        editorClient.notify(TextDocument.publishDiagnostics.method, msg)
      }
  }
}
