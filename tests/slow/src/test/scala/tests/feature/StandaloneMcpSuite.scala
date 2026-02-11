package tests.feature

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.mcp.StandaloneMcpService
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite
import tests.FileLayout
import tests.SbtBuildLayout
import tests.mcp.TestMcpClient

/**
 * Integration tests for standalone MCP server mode.
 *
 * These tests verify that the standalone MCP server can initialize and
 * provide MCP functionality without requiring an LSP client connection.
 */
class StandaloneMcpSuite extends BaseLspSuite("standalone-metals-suite") {

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  private val scheduledExecutor =
    Executors.newSingleThreadScheduledExecutor()

  private val portToUse: Int = {
    val socket = new java.net.ServerSocket(0)
    val port = socket.getLocalPort()
    socket.close()
    port
  }

  override def afterAll(): Unit = {
    ec.shutdown()
    scheduledExecutor.shutdown()
    try {
      if (!ec.awaitTermination(5, TimeUnit.SECONDS)) {
        ec.shutdownNow()
      }
      if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        scheduledExecutor.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        ec.shutdownNow()
        scheduledExecutor.shutdownNow()
    }
  }

  test("standalone-mcp-service-initialization") {
    val layout = SbtBuildLayout(
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  def main(args: Array[String]): Unit = {
         |    println("Hello, World!")
         |  }
         |}
         |""".stripMargin,
      V.scala213,
    )
    val workspace = createWorkspace("standalone-metals")
    FileLayout.fromString(layout, workspace)
    val service = new StandaloneMcpService(
      workspace = workspace,
      port = Some(portToUse),
      scheduledExecutor = scheduledExecutor,
    )

    // Test that service can be initialized (start() awaits MCP server readiness)
    service.start()
    val client =
      new TestMcpClient(s"http://localhost:$portToUse", portToUse)

    for {
      _ <- client.initialize()
      modules <- client.listModules()
      _ <- client.shutdown()
      _ = service.cancel()
    } yield {

      service.cancel()
      try {
        RecursivelyDelete(workspace)
      } catch {
        case NonFatal(_) =>
      }
      val preambule = modules.linesIterator.take(1).toList.head
      val modulesList =
        modules.linesIterator.drop(1).toList.sorted.mkString("\n")

      assertNoDiff(
        s"$preambule\n$modulesList",
        """|Available modules (build targets):
           |- a
           |- a-test
           |- b
           |- b-test
           |- standalone-metals
           |- standalone-metals-build
           |- standalone-metals-test
           |""".stripMargin,
      )
    }

  }

}
