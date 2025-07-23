package tests.mcp

import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.McpMessages
import scala.meta.internal.metals.mcp.NoClient
import scala.meta.internal.metals.mcp.VSCodeEditor

import tests.BaseLspSuite

class McpServerLspSuite extends BaseLspSuite("mcp-server") with McpTestUtils {

  // get a new random port that is not in use
  val portToUse: Int = {
    val socket = new java.net.ServerSocket(0)
    val port = socket.getLocalPort()
    socket.close()
    port
  }

  test("find-dep") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.metals/mcp.json
           |{
           |  "servers": {
           |    "root-metals": {
           |      "url": "http://localhost:${portToUse}/sse"
           |    }
           |  }
           |}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      _ = assertEquals(client.port, portToUse)
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

  test("list-modules") {
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
      client <- startMcpServer()
      modules <- client.listModules()
      _ = assertNoDiff(
        modules,
        """|Available modules (build targets):
           |- a
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    assertEquals(
      McpConfig.readPort(server.workspace, "root", VSCodeEditor),
      None,
    )
    assert(
      McpConfig.readPort(server.workspace, "root", NoClient).isDefined,
      "MCP server port should be defined in the default location",
    )
  }

}
