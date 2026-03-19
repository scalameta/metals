package scala.meta.internal.metals.mcp

import scala.concurrent.ExecutionContext

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.Compilations
import scala.meta.internal.metals.ConnectionProvider
import scala.meta.internal.metals.Diagnostics
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.io.AbsolutePath

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import org.eclipse.lsp4j.services.LanguageClient

/**
 * MCP server implementation using stdio transport.
 *
 * This server communicates via standard input/output streams, making it suitable
 * for direct process integration with MCP clients like Claude Desktop.
 * Messages are exchanged as newline-delimited JSON-RPC messages.
 *
 * @see [[MetalsMcpServer]] for HTTP transport variant
 */
class MetalsMcpStdioServer(
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
    val transportProvider = new StdioServerTransportProvider(jsonMapper)

    val serverName = s"$projectName-metals"
    val asyncServer = McpServer
      .async(transportProvider)
      .serverInfo(serverName, BuildInfo.metalsVersion)
      .capabilities(buildCapabilities())
      .build()

    cancelable.add(() => asyncServer.close())

    registerAllTools(asyncServer)

    scribe.info(s"Metals MCP stdio server started for project: $serverName")
  }
}
