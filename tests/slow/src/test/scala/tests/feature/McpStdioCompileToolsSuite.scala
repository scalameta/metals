package tests.feature

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseLspSuite
import tests.FileLayout
import tests.SbtBuildLayout
import tests.mcp.TestMcpStdioClient

class McpStdioCompileToolsSuite
    extends BaseLspSuite("mcp-stdio-compile-tools") {

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  override def afterAll(): Unit = {
    ec.shutdown()
    try {
      if (!ec.awaitTermination(5, TimeUnit.SECONDS)) {
        ec.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        ec.shutdownNow()
    }
  }

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

  private def createStdioClient(workspace: AbsolutePath): TestMcpStdioClient = {
    TestMcpStdioClient(workspace.toNIO)
  }

  test("stdio-compile-file-works") {
    val workspacePath = createTestWorkspace("stdio-compile-file-test")
    val client = createStdioClient(workspacePath)

    val result = for {
      _ <- client.initialize()
      r <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
    } yield r

    result.transformWith { res =>
      for {
        _ <- client.shutdown().recover { case NonFatal(_) => () }
        _ = client.cleanup()
      } yield {
        try RecursivelyDelete(workspacePath)
        catch { case NonFatal(_) => }
        res match {
          case scala.util.Success(r) =>
            assert(r.nonEmpty, s"Should return compile result, got: $r")
          case scala.util.Failure(e) =>
            throw e
        }
      }
    }
  }

  test("stdio-compile-module-works") {
    val workspacePath = createTestWorkspace("stdio-compile-module-test")
    val client = createStdioClient(workspacePath)

    val result = for {
      _ <- client.initialize()
      r <- client.compileModule("a")
    } yield r

    result.transformWith { res =>
      for {
        _ <- client.shutdown().recover { case NonFatal(_) => () }
        _ = client.cleanup()
      } yield {
        try RecursivelyDelete(workspacePath)
        catch { case NonFatal(_) => }
        res match {
          case scala.util.Success(r) =>
            assert(r.nonEmpty, s"Should return compile result, got: $r")
          case scala.util.Failure(e) =>
            throw e
        }
      }
    }
  }

  test("stdio-compile-full-works") {
    val workspacePath = createTestWorkspace("stdio-compile-full-test")
    val client = createStdioClient(workspacePath)

    val result = for {
      _ <- client.initialize()
      r <- client.compileFull()
    } yield r

    result.transformWith { res =>
      for {
        _ <- client.shutdown().recover { case NonFatal(_) => () }
        _ = client.cleanup()
      } yield {
        try RecursivelyDelete(workspacePath)
        catch { case NonFatal(_) => }
        res match {
          case scala.util.Success(r) =>
            assert(r.nonEmpty, s"Should return compile result, got: $r")
          case scala.util.Failure(e) =>
            throw e
        }
      }
    }
  }
}
