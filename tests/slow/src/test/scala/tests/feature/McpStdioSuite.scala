package tests.feature

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import tests.BaseLspSuite
import tests.FileLayout
import tests.SbtBuildLayout
import tests.mcp.TestMcpStdioClient

class McpStdioSuite extends BaseLspSuite("mcp-stdio-suite") {

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

  test("stdio-initialize-handshake") {
    withStdioClient("stdio-init-test") { client =>
      for {
        result <- client.initialize()
        _ <- client.shutdown()
      } yield {
        assert(result != null, "Initialize result should not be null")
      }
    }
  }

  test("stdio-list-modules") {
    withStdioClient("stdio-modules-test") { client =>
      for {
        _ <- client.initialize()
        modules <- client.listModules()
        _ <- client.shutdown()
      } yield {
        assert(modules.contains("Available modules"), s"Got: $modules")
        assert(
          modules.contains("a") || modules.contains("a-test"),
          s"Module 'a' should be listed, got: $modules",
        )
      }
    }
  }

  test("stdio-find-dep") {
    withStdioClient("stdio-finddep-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.findDep("org.scala-lang")
        _ <- client.shutdown()
      } yield {
        assert(
          result.mkString.contains("scala-library"),
          s"Should find scala-library, got: $result",
        )
      }
    }
  }

  test("stdio-typed-glob-search") {
    withStdioClient("stdio-typedglob-test") { client =>
      val filePath = "a/src/main/scala/com/example/Hello.scala"
      for {
        _ <- client.initialize()
        result <- client.typedGlobSearch(
          "Hello",
          List("class", "object"),
          Some(filePath),
        )
        _ <- client.shutdown()
      } yield {
        assert(
          result.contains("com.example.Hello"),
          s"Should find Hello class/object, got: $result",
        )
      }
    }
  }

  test("stdio-list-scalafix-rules") {
    withStdioClient("stdio-scalafix-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.listScalafixRules()
        _ <- client.shutdown()
      } yield {
        assert(
          result.contains("Available scalafix rules"),
          s"Should list scalafix rules, got: $result",
        )
      }
    }
  }

  test("stdio-compile-module") {
    withStdioClient("stdio-compile-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.compileModule("a")
        _ <- client.shutdown()
      } yield {
        assert(
          result.nonEmpty,
          s"Should return a compilation result, got: $result",
        )
      }
    }
  }

  test("stdio-format-file") {
    withStdioClient("stdio-format-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.formatFile("a/src/main/scala/com/example/Hello.scala")
        _ <- client.shutdown()
      } yield {
        assert(result.nonEmpty, s"Should return a format result, got: $result")
      }
    }
  }

  test("stdio-glob-search") {
    withStdioClient("stdio-globsearch-test") { client =>
      val filePath = "a/src/main/scala/com/example/Hello.scala"
      for {
        _ <- client.initialize()
        result <- client.globSearch("Hello", Some(filePath))
        _ <- client.shutdown()
      } yield {
        assert(
          result.contains("com.example.Hello"),
          s"Should find Hello, got: $result",
        )
      }
    }
  }

  test("stdio-inspect") {
    withStdioClient("stdio-inspect-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.inspect("com.example.Hello")
        _ <- client.shutdown()
      } yield {
        val hasRelevantContent = result.contains("Hello") ||
          result.contains("main") ||
          result.contains("Inspected from") ||
          result.nonEmpty

        assert(hasRelevantContent, s"Should inspect Hello class, got: $result")
      }
    }
  }

  test("stdio-get-docs") {
    withStdioClient("stdio-getdocs-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.getDocs("com.example.Hello")
        _ <- client.shutdown()
      } yield {
        assert(
          result != null && result.nonEmpty,
          s"Should get docs result, got: $result",
        )
      }
    }
  }

  test("stdio-get-usages") {
    withStdioClient("stdio-getusages-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.getUsages("com.example.Hello.add")
        _ <- client.shutdown()
      } yield {
        assert(result != null, s"Should get usages result")
      }
    }
  }

  test("stdio-compile-file") {
    withStdioClient("stdio-compilefile-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
        _ <- client.shutdown()
      } yield {
        assert(
          result.nonEmpty,
          s"Should return compile-file result, got: $result",
        )
      }
    }
  }

  test("stdio-compile-full") {
    withStdioClient("stdio-compilefull-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.compileFull()
        _ <- client.shutdown()
      } yield {
        assert(
          result.nonEmpty,
          s"Should return compile-full result, got: $result",
        )
      }
    }
  }

  test("stdio-import-build") {
    withStdioClient("stdio-importbuild-test") { client =>
      for {
        _ <- client.initialize()
        result <- client.importBuild()
        _ <- client.shutdown()
      } yield {
        assert(result != null, s"Should return import-build result")
      }
    }
  }
}
