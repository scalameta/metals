package tests.feature

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseSuite
import tests.FileLayout
import tests.SbtBuildLayout
import tests.WorkspaceHelper
import tests.mcp.TestMcpStdioClient

class McpStdioCompileToolsSuite extends BaseSuite with WorkspaceHelper {
  def suiteName: String = "mcp-stdio-compile-tools"

  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  private def createTestWorkspace(name: String): AbsolutePath = {
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
    val workspace = createWorkspace(name)
    FileLayout.fromString(layout, workspace)
    workspace
  }

  private def withStdioClient[T](
      name: String
  )(f: TestMcpStdioClient => Future[T]): Future[T] = {
    val workspacePath = createTestWorkspace(name)
    val client = TestMcpStdioClient(workspacePath.toNIO)

    f(client)
      .andThen { case _ =>
        client.cleanup()
      }
      .andThen { case _ =>
        try RecursivelyDelete(workspacePath)
        catch { case NonFatal(_) => }
      }
  }

  test("stdio-compile-file-works") {
    withStdioClient("stdio-compile-file-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }

  test("stdio-compile-module-works") {
    withStdioClient("stdio-compile-module-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileModule("a")
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }

  test("stdio-compile-full-works") {
    withStdioClient("stdio-compile-full-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileFull()
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }
}
