package tests.mcp

import scala.concurrent.Promise
import scala.util.Random

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.NoClient

import org.eclipse.lsp4j.MessageParams
import tests.BaseLspSuite

class McpPortConfigSuite extends BaseLspSuite("mcp-port-config") {
  override def clientName: String = "unknown client"

  override def userConfig: UserConfiguration =
    super.userConfig.copy(startMcpServer = true)

  test("mcp port is correctly saved") {
    cleanWorkspace()

    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
    } yield {
      val port = McpConfig.readPort(workspace, "root", NoClient)
      assert(port.isDefined)
    }
  }

  test("mcp port is correctly read from config") {
    cleanWorkspace()

    val port = Random.nextInt(55535) + 10000
    McpConfig.writeConfig(port, "root", workspace, NoClient, Set.empty)

    val waitForMessage = Promise[Unit]
    client.showMessageHandler = {
      case message: MessageParams
          if message
            .getMessage()
            .contains("Metals MCP server started on port") =>
        assert(message.getMessage().contains(port.toString()))
        waitForMessage.success(())
      case _ =>
    }
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- waitForMessage.future
    } yield ()
  }
}
