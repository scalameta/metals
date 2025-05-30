package tests.mcp

import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.VSCodeEditor

import tests.BaseLspSuite
import tests.mcp.TestMcpClient

class McpServerLspSuite extends BaseLspSuite("mcp-server") {

  test("find-dep") {
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
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      _ = assertNoDiagnostics()
      _ <- server.didChangeConfiguration(
        UserConfiguration(startMcpServer = true).toString
      )
      port <- Future.successful(
        McpConfig.readPort(server.workspace, "root", VSCodeEditor)
      )
      _ = assert(port.isDefined, "MCP server port should be defined")
      client = new TestMcpClient(s"http://localhost:${port.get}/sse")
      _ <- client.initialize()
      result <- client.findDep("org.scala-lan")
      _ = assertNoDiff(
        result.mkString("\n"),
        "Tool managed to complete organization and got potential matches: org.scala-lang, org.scala-lang-osgi",
      )
      resultName <- client.findDep("org.scala-lang", Some("scala-library"))
      _ = assertNoDiff(
        resultName.mkString("\n"),
        "Tool managed to complete name and got potential matches: scala-library, scala-library-all",
      )
      resultNameVersion <- client.findDep(
        "org.scalameta",
        Some("parsers"),
        Some("4.10."),
      )
      _ = assertNoDiff(
        resultNameVersion.mkString("\n"),
        "Tool managed to complete version and got potential matches: 4.10.2, 4.10.1, 4.10.0",
      )
      _ <- client.shutdown()
    } yield ()
  }

}
