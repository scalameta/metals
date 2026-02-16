package scala.meta.internal.metals.mcp

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.metals.doctor.HeadDoctor
import scala.meta.io.AbsolutePath

/**
 * Standalone MCP service that initializes Metals components for MCP server mode
 * without requiring an LSP client connection.
 *
 * This service provides a lightweight way to run Metals as a standalone MCP server,
 * enabling AI agents and tools to interact with Scala projects directly.
 *
 * @param workspace The workspace root path
 * @param port Optional port for HTTP transport
 * @param scheduledExecutor Scheduled executor for background tasks
 * @param client Client to generate config for (defaults to NoClient)
 */
class StandaloneMcpService(
    workspace: AbsolutePath,
    port: Option[Int],
    scheduledExecutor: ScheduledExecutorService,
    client: Client = NoClient,
)(implicit ec: ExecutionContextExecutorService)
    extends Cancelable {
  port match {
    case Some(port) =>
      McpConfig.writeConfig(port, workspace.filename, workspace, NoClient)
    case None => // random port will be assigned by the system
  }

  private val cancelables = new MutableCancelable()
  private val isCancelled = new AtomicBoolean(false)

  private val time: Time = Time.system

  private val clientConfig: ClientConfiguration = ClientConfiguration(
    MetalsServerConfig.default
  )

  private val mcpClient = new ConfiguredLanguageClient(
    new McpLanguageClient(workspace),
    clientConfig,
    None,
  )

  private val statusBar: StatusBar = new StatusBar(mcpClient, time)

  private val timerProvider: TimerProvider = new TimerProvider(time)

  private val workDoneProgress: WorkDoneProgress =
    new WorkDoneProgress(mcpClient, time)

  private val headDoctor = new HeadDoctor(
    () => List.empty,
    () => Future.successful(None),
    clientConfig,
    mcpClient,
    isHttpEnabled = false,
  )

  private lazy val moduleStatus: ModuleStatus =
    new ModuleStatus(
      mcpClient,
      () => None,
      (path) => projectMetalsLspService,
      clientConfig.icons(),
    )

  private val bspStatus = new BspStatus(
    mcpClient,
    isBspStatusProvider = false,
  )

  lazy val projectMetalsLspService = new ProjectMetalsLspService(
    ec,
    scheduledExecutor,
    MetalsServerInputs.productionConfiguration,
    mcpClient,
    StandaloneMcpService.defaultInitializeParams,
    clientConfig,
    statusBar,
    () => None,
    timerProvider,
    () => (),
    workspace,
    None,
    headDoctor,
    bspStatus,
    workDoneProgress,
    maxScalaCliServers = 3,
    moduleStatus,
  )

  /**
   * Run the MCP server. This blocks until shutdown.
   */
  def startAndBlock(): Unit = {
    start()
    // Block until shutdown signal
    synchronized {
      while (!isCancelled.get()) {
        try {
          wait(1000)
        } catch {
          case _: InterruptedException =>
            isCancelled.set(true)
        }
      }
    }
  }

  def start(): Unit = {
    scribe.info("Starting MCP server...")
    Await.result(projectMetalsLspService.initialized(), 10.minutes)
    Await.result(projectMetalsLspService.startMcpServer(), 2.minutes)
    cancelables.add(projectMetalsLspService)
    val createdPort =
      McpConfig.readPort(workspace, workspace.filename, NoClient)
    (createdPort, client) match {
      case (_, NoClient) => // no client was set, nothing to do
      case (Some(port), client) =>
        McpConfig.writeConfig(port, workspace.filename, workspace, client)
      case (None, _) => scribe.error("No port was created")
    }
    scribe.info("MCP server started successfully")
  }

  override def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      scribe.info("Cancelling standalone MCP service...")
      try {
        cancelables.cancel()
      } catch {
        case NonFatal(e) =>
          scribe.warn("Error cancelling services", e)
      }
      // Wake up the startAndBlock() method
      synchronized {
        notifyAll()
      }

      scribe.info("Standalone MCP service cancelled")
    }
  }

}

object StandaloneMcpService {
  import org.eclipse.lsp4j.InitializeParams
  import org.eclipse.lsp4j.ClientCapabilities
  import org.eclipse.lsp4j.ClientInfo

  // Default initialize params for standalone mode
  val defaultInitializeParams: InitializeParams = {
    val params = new InitializeParams()
    params.setCapabilities(new ClientCapabilities())
    val clientInfo = new ClientInfo()
    clientInfo.setName("metals-mcp-standalone")
    clientInfo.setVersion(BuildInfo.metalsVersion)
    params.setClientInfo(clientInfo)
    params
  }
}
