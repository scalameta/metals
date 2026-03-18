package tests.feature

import java.nio.file.Path
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

  private def createStdioClient(workspace: AbsolutePath): TestMcpStdioClient = {
    TestMcpStdioClient(workspace.toNIO)
  }

  test("stdio-initialize-handshake") {
    val workspacePath = createTestWorkspace("stdio-init-test")
    val client = createStdioClient(workspacePath)

    for {
      result <- client.initialize()
      _ = assert(result != null, "Initialize result should not be null")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }
    }
  }

  test("stdio-list-modules") {
    val workspacePath = createTestWorkspace("stdio-modules-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      modules <- client.listModules()
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(modules.contains("Available modules"), s"Got: $modules")
      assert(
        modules.contains("a") || modules.contains("a-test"),
        s"Module 'a' should be listed, got: $modules",
      )
    }
  }

  test("stdio-find-dep") {
    val workspacePath = createTestWorkspace("stdio-finddep-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.findDep("org.scala-lang")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.mkString.contains("scala-library"),
        s"Should find scala-library, got: $result",
      )
    }
  }

  test("stdio-typed-glob-search") {
    val workspacePath = createTestWorkspace("stdio-typedglob-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.typedGlobSearch("Hello", List("class", "object"))
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.contains("com.example.Hello"),
        s"Should find Hello class/object, got: $result",
      )
    }
  }

  test("stdio-list-scalafix-rules") {
    val workspacePath = createTestWorkspace("stdio-scalafix-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.listScalafixRules()
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.contains("Available scalafix rules"),
        s"Should list scalafix rules, got: $result",
      )
    }
  }

  test("stdio-compile-module") {
    val workspacePath = createTestWorkspace("stdio-compile-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.compileModule("a")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.nonEmpty,
        s"Should return a compilation result, got: $result",
      )
    }
  }

  test("stdio-format-file") {
    val workspacePath = createTestWorkspace("stdio-format-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.formatFile("a/src/main/scala/com/example/Hello.scala")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(result.nonEmpty, s"Should return a format result, got: $result")
    }
  }

  test("stdio-glob-search") {
    val workspacePath = createTestWorkspace("stdio-globsearch-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.globSearch("Hello")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.contains("com.example.Hello"),
        s"Should find Hello, got: $result",
      )
    }
  }

  test("stdio-inspect") {
    val workspacePath = createTestWorkspace("stdio-inspect-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.inspect("com.example.Hello")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.contains("Hello") || result.contains("main"),
        s"Should inspect Hello class, got: $result",
      )
    }
  }

  test("stdio-get-docs") {
    val workspacePath = createTestWorkspace("stdio-getdocs-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.getDocs("com.example.Hello")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result != null && result.nonEmpty,
        s"Should get docs result, got: $result",
      )
    }
  }

  test("stdio-get-usages") {
    val workspacePath = createTestWorkspace("stdio-getusages-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.getUsages("com.example.Hello.add")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(result != null, s"Should get usages result")
    }
  }

  test("stdio-compile-file") {
    val workspacePath = createTestWorkspace("stdio-compilefile-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.nonEmpty,
        s"Should return compile-file result, got: $result",
      )
    }
  }

  test("stdio-compile-full") {
    val workspacePath = createTestWorkspace("stdio-compilefull-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.compileFull()
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(
        result.nonEmpty,
        s"Should return compile-full result, got: $result",
      )
    }
  }

  test("stdio-import-build") {
    val workspacePath = createTestWorkspace("stdio-importbuild-test")
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result <- client.importBuild()
      _ <- client.shutdown()
      _ = client.cleanup()
    } yield {
      try {
        RecursivelyDelete(workspacePath)
      } catch {
        case NonFatal(_) =>
      }

      assert(result != null, s"Should return import-build result")
    }
  }
}
