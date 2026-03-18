package tests.feature

import java.nio.file.Paths
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

  private def createTestWorkspace(
      name: String,
      sourceCode: String,
  ): AbsolutePath = {
    val layout = SbtBuildLayout(sourceCode, V.scala213)
    val workspace = createWorkspace(name)
    FileLayout.fromString(layout, workspace)
    workspace
  }

  private def createStdioClient(workspace: AbsolutePath): TestMcpStdioClient = {
    TestMcpStdioClient(workspace.toNIO)
  }

  private def fixSourceFile(workspacePath: AbsolutePath): Unit = {
    val sourceFile =
      workspacePath.resolve("a/src/main/scala/com/example/Hello.scala")
    val fixedContent =
      """|package com.example
         |
         |object Hello {
         |  def main(args: Array[String]): Unit = {
         |    val x: Int = 42
         |    println(x)
         |  }
         |}
         |""".stripMargin
    java.nio.file.Files.write(sourceFile.toNIO, fixedContent.getBytes)
  }

  test("stdio-compile-file") {
    val workspacePath = createTestWorkspace(
      "stdio-compile-file-test",
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  def main(args: Array[String]): Unit = {
         |    val x: String = 42
         |    println(x)
         |  }
         |}
         |""".stripMargin,
    )
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result1 <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ = assert(
        result1.contains("Found errors in"),
        s"Should contain 'Found errors in', got: $result1",
      )
      _ = assert(
        result1.contains("a/src/main/scala/com/example/Hello.scala") ||
          result1.contains("a\\src\\main\\scala\\com\\example\\Hello.scala"),
        s"Should contain file path, got: $result1",
      )
      _ = assert(
        result1.contains("type mismatch"),
        s"Should contain 'type mismatch', got: $result1",
      )
      _ = Future.successful(fixSourceFile(workspacePath))
      result2 <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ = assert(
        result2 == "Compilation successful.",
        s"Expected 'Compilation successful.', got: $result2",
      )
      result3 <- client.compileFile("non-existent.scala")
      _ = assert(
        result3.contains("Error: File not found"),
        s"Should contain 'Error: File not found', got: $result3",
      )
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

  test("stdio-compile-module") {
    val workspacePath = createTestWorkspace(
      "stdio-compile-module-test",
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  def main(args: Array[String]): Unit = {
         |    val x: String = 42
         |    println(x)
         |  }
         |}
         |""".stripMargin,
    )
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result1 <- client.compileModule("a")
      _ = assert(
        result1.contains("Found errors in the module"),
        s"Should contain 'Found errors in the module', got: $result1",
      )
      _ = assert(
        result1.contains("type mismatch"),
        s"Should contain 'type mismatch', got: $result1",
      )
      _ = Future.successful(fixSourceFile(workspacePath))
      result2 <- client.compileModule("a")
      _ = assert(
        result2 == "Compilation successful.",
        s"Expected 'Compilation successful.', got: $result2",
      )
      result3 <- client.compileModule("non-existent")
      _ = assert(
        result3.contains("Error: Module not found"),
        s"Should contain 'Error: Module not found', got: $result3",
      )
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

  test("stdio-compile-file-with-warnings") {
    val workspacePath = createTestWorkspace(
      "stdio-compile-file-warnings-test",
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  import scala.collection.mutable.Map
         |  
         |  def main(args: Array[String]): Unit = {
         |    println("Hello, World!")
         |  }
         |}
         |""".stripMargin,
    )
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result1 <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ = assert(
        result1.contains("Found warnings"),
        s"Should contain 'Found warnings', got: $result1",
      )
      _ = assert(
        result1.contains("Unused import"),
        s"Should contain 'Unused import', got: $result1",
      )
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

  test("stdio-compile-full-with-warnings") {
    val workspacePath = createTestWorkspace(
      "stdio-compile-full-warnings-test",
      """|/a/src/main/scala/com/example/Hello.scala
         |package com.example
         |
         |object Hello {
         |  import scala.collection.mutable.Map
         |  
         |  def main(args: Array[String]): Unit = {
         |    println("Hello, World!")
         |  }
         |}
         |""".stripMargin,
    )
    val client = createStdioClient(workspacePath)

    for {
      _ <- client.initialize()
      result1 <- client.compileFull()
      _ = assert(
        result1.contains("Compilation successful with warnings"),
        s"Should contain 'Compilation successful with warnings', got: $result1",
      )
      _ = assert(
        result1.contains("Unused import"),
        s"Should contain 'Unused import', got: $result1",
      )
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
}
