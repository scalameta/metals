package scala.meta.metals.bsp

import scala.meta.metals.Configuration
import monix.eval.Task
import monix.execution.CancelableFuture
import monix.execution.Scheduler
import org.langmeta.io.AbsolutePath
import scala.meta.jsonrpc.JsonRpcClient
import scala.meta.jsonrpc.Services
import scala.meta.lsp.TextDocument
import scala.meta.lsp.Window
import ch.epfl.`scala`.bsp.endpoints
import ch.epfl.`scala`.bsp.InitializeBuildParams
import ch.epfl.`scala`.bsp.WorkspaceBuildTargetsRequest
import ch.epfl.`scala`.bsp.Uri
import ch.epfl.`scala`.bsp.BuildClientCapabilities
import ch.epfl.`scala`.bsp.CompileParams
import scala.util.control.NonFatal
import com.typesafe.scalalogging.LazyLogging

/**
 * A wrapper around a connection to a bsp server.
 *
 * @param client client that can send requests and notifications
 *               to the sbt server.
 * @param runningServer The running client listening for requests from the server.
 *               Use runningServer.onComplete to attach callbacks on
 *               disconnect.
 *
 */
case class BspServer(
    client: JsonRpcClient,
    runningServer: CancelableFuture[Unit],
) extends LazyLogging {
  implicit val _client = client
  def compile()(implicit scheduler: Scheduler): Unit = {
    val compileTask = for {
      result <- endpoints.Workspace.buildTargets
        .request(WorkspaceBuildTargetsRequest())
      // FIXME(gabro): here we're blindly compiling the first returned target
      // we need a better strategy to either automatically select a target for
      // compilation or making the user select one
      target = result.right.get.targets.head
      _ <- endpoints.BuildTarget.compile.request(
        CompileParams(
          targets = List(target.id),
          requestId = None, // this is originId in recent versions of bsp
          arguments = Nil
        )
      )
    } yield ()
    compileTask.onErrorRecover {
      case NonFatal(err) => logger.error("Failed to send compile via bsp", err)
    }.runAsync
  }
  def disconnect(): Unit = runningServer.cancel()
}

object BspServer extends LazyLogging {
  private def fail(message: String) = Task.now(Left(message))

  /**
   * Establish connection with bsp server.
   *
   * @param cwd The workspace directory, baseDirectory.in(ThisBuild).
   * @param services the handler for requests/notifications/responses from
   *                 the sbt server.
   * @param scheduler the scheduler on which to run the services handling
   *                  sbt responses and notifications.
   * @return A client to communicate with bsp server in case of success or a
   *         user-friendly error message if something went wrong in case of
   *         failure.
   */
  def connect(
      cwd: AbsolutePath,
      bspConnection: BspConnector,
  ): Task[Either[String, BspServer]] = {
    bspConnection.openServerConnection().flatMap {
      case Left(error) => Task(Left(error))
      case Right((client, runningServer)) =>
        val initialize =
          endpoints.Build.initialize
            .request(
              InitializeBuildParams(
                rootUri = Uri(cwd.toURI),
                capabilities = BuildClientCapabilities(
                  languageIds = List("scala"),
                  providesFileWatching = false
                )
              )
            )(client)
        initialize
        // uncomment the next line to trigger Task timeout
        // .timeout(concurrent.duration.Duration(3, "seconds"))
          .map { _ =>
            Right(BspServer(client, runningServer)) //, logger))
          }
          .onErrorRecover {
            case NonFatal(e) =>
              logger.error(e.getMessage)
              Left(e.getMessage)
          }
    }
  }

  /**
   * Handler that forwards logMessage and publishNotifications from the bsp server.
   *
   * @param editorClient the LSP editor client to forward the notifications
   *                     from the sbt server.
   */
  def forwardingServices(
      editorClient: JsonRpcClient,
      config: () => Configuration,
  ): Services =
    Services.empty
      .notification(Window.logMessage) { msg =>
        editorClient.notify(Window.logMessage, msg)
      }
      .notification(TextDocument.publishDiagnostics) { msg =>
        if (config().bsp.enabled.enabled) {
          editorClient.notify(TextDocument.publishDiagnostics, msg)
        }
      }

}
