package tests.mcp

import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.VSCodeEditor

import tests.BaseLspSuite

trait McpTestUtils {
  self: BaseLspSuite =>

  def startMcpServer(): Future[TestMcpClient] =
    for {
      _ <- server.didChangeConfiguration(
        UserConfiguration(startMcpServer = true).toString
      )
      port <- Future.successful(
        McpConfig.readPort(server.workspace, "root", VSCodeEditor)
      )
      _ = assert(port.isDefined, "MCP server port should be defined")
      client = new TestMcpClient(s"http://localhost:${port.get}", port.get)
      _ <- client.initialize()
    } yield client
}
