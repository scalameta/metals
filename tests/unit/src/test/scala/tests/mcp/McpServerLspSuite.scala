package tests.mcp

import scala.concurrent.Future

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.McpMessages
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
        McpMessages.FindDep.dependencyReturnMessage(
          "organization",
          Seq("org.scala-lang", "org.scala-lang-osgi"),
        ),
      )
      resultName <- client.findDep("org.scala-lang", Some("scala-librar"))
      _ = assertNoDiff(
        resultName.mkString("\n"),
        McpMessages.FindDep.dependencyReturnMessage(
          "name",
          Seq("scala-library", "scala-library-all"),
        ),
      )
      resultNameVersion <- client.findDep(
        "org.scalameta",
        Some("parsers"),
        Some("4.10."),
      )
      _ = assertNoDiff(
        resultNameVersion.mkString("\n"),
        McpMessages.FindDep
          .dependencyReturnMessage("version", Seq("4.10.2", "4.10.1", "4.10.0")),
      )
      resultNameVersion2 <- client.findDep(
        "org.scalameta",
        Some("parsers_2.13"),
        None,
      )
      _ = assert(
        resultNameVersion2.head.contains(", 4.13.0,"),
        s"Expected to contain version 4.13.0, got ${resultNameVersion2.mkString("\n")}",
      )
      _ <- client.shutdown()
    } yield ()
  }

}
