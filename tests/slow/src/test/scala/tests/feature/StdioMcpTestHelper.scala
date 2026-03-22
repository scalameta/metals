package tests.feature

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.FileLayout
import tests.SbtBuildLayout
import tests.WorkspaceHelper
import tests.mcp.TestMcpStdioClient

trait StdioMcpTestHelper extends WorkspaceHelper { self: munit.FunSuite =>

  protected def createTestWorkspace(name: String): AbsolutePath = {
    val layout = SbtBuildLayout(
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  def main(args: Array[String]): Unit = {
         |    println("Hello, World!")
         |  }
         |  
         |  def add(a: Int, b: Int): Int = a + b
         |}
         |""".stripMargin,
      V.scala213,
    )
    val workspace = createWorkspace(name)
    FileLayout.fromString(layout, workspace)
    workspace
  }

  protected def withStdioClient[T](
      name: String
  )(f: TestMcpStdioClient => Future[T])(implicit
      ec: ExecutionContext
  ): Future[T] = {
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
}
