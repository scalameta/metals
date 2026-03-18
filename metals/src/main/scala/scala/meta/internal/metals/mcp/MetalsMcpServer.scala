package scala.meta.internal.metals.mcp

import java.net.InetSocketAddress

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.io.AbsolutePath

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.InstanceHandle
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient

/**
 * MCP server implementation using HTTP transport.
 *
 * This server uses Undertow with a servlet-based transport, making it suitable
 * for integration with MCP clients that support HTTP/SSE transport.
 *
 * @see [[MetalsMcpStdioServer]] for stdio transport variant
 */
class MetalsMcpServer(
    protected val queryEngine: McpQueryEngine,
    protected val projectPath: AbsolutePath,
    protected val compilations: Compilations,
    protected val focusedDocument: () => Option[AbsolutePath],
    protected val diagnostics: Diagnostics,
    protected val buildTargets: BuildTargets,
    protected val mcpTestRunner: McpTestRunner,
    protected val clientName: String,
    protected val projectName: String,
    protected val languageClient: LanguageClient,
    protected val connectionProvider: ConnectionProvider,
    protected val scalaVersionSelector: ScalaVersionSelector,
    protected val formattingProvider: FormattingProvider,
    protected val scalafixLlmRuleProvider: ScalafixLlmRuleProvider,
)(implicit
    protected val ec: ExecutionContext
) extends MetalsMcpTools {

  def run(): Unit = {

    val servlet = new HttpServletStreamableServerTransportProvider.Builder()
      .jsonMapper(jsonMapper)
      .mcpEndpoint(MetalsMcpServer.mcpEndpoint)
      .build()

    val serverName = s"$projectName-metals"
    val asyncServer = McpServer
      .async(servlet)
      .serverInfo(serverName, BuildInfo.metalsVersion)
      .capabilities(buildCapabilities())
      .build()

    cancelable.add(() => asyncServer.close())

    registerAllTools(asyncServer)

    // serve servlet
    val servletDeployment = Servlets
      .deployment()
      .setClassLoader(classOf[MetalsMcpServer].getClassLoader())
      .setContextPath("/")
      .setDeploymentName("sse-server.war")
      .addServlets(
        Servlets
          .servlet(
            "SseServlet",
            classOf[HttpServletStreamableServerTransportProvider],
            () =>
              new InstanceHandle[HttpServletStreamableServerTransportProvider] {
                override def getInstance()
                    : HttpServletStreamableServerTransportProvider = servlet
                override def release(): Unit = { servlet.close() }

              },
          )
          .setAsyncSupported(true)
          .addMapping("/*")
      )

    val manager = Servlets.defaultContainer()
    val deployment = manager.addDeployment(servletDeployment)
    deployment.deploy()

    // Read port from the default config file in .metals/ directory
    def readPort(client: Client) =
      McpConfig.readPort(projectPath, projectName, client)
    val savedClientConfigPort = readPort(client)
    val savedConfigPort = savedClientConfigPort.orElse(readPort(NoClient))
    val undertowServer = Undertow
      .builder()
      .addHttpListener(savedConfigPort.getOrElse(0), "localhost")
      .setHandler(deployment.start())
      .build()
    undertowServer.start()

    val listenerInfo = undertowServer.getListenerInfo()
    val port =
      listenerInfo.get(0).getAddress().asInstanceOf[InetSocketAddress].getPort()

    val activeClientExtensionIds =
      MetalsServerConfig.default.activeClientExtensionIds

    if (savedConfigPort.isEmpty) {
      McpConfig.writeConfig(
        port,
        projectName,
        projectPath,
        NoClient,
        activeClientExtensionIds,
      )
    }

    if (savedClientConfigPort.isEmpty) {
      McpConfig.writeConfig(
        port,
        projectName,
        projectPath,
        client,
        activeClientExtensionIds,
      )
    }

    McpConfig.rewriteOldEndpointIfNeeded(projectPath, projectName, client, port)

    languageClient.showMessage(
      new MessageParams(
        MessageType.Info,
        s"Metals MCP server started on port: $port. Refresh connection if needed.",
      )
    )

    scribe.info(
      s"To connect to Metals MCP server use `http` transport type and url: http://localhost:$port/mcp."
    )

    cancelable.add(() => undertowServer.stop())
  }

  override def cancel(): Unit = {
    // Remove the config so that next time the editor will only connect after mcp server is started
    if (client != NoClient && client.shouldCleanUpServerEntry) {
      McpConfig.deleteConfig(projectPath, projectName, client)
    }
    cancelable.cancel()
  }
}

object MetalsMcpServer {
  val mcpEndpoint = "/mcp"
}
