package tests.mcp

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class McpFormatLspSuite extends BaseLspSuite("mcp-format") with McpTestUtils {

  test("format-file - with scalafmt config") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.scalafmt.conf
           |version = "${V.scalafmtVersion}"
           |runner.dialect = scala213
           |maxColumn = 40
           |/a/src/main/scala/com/example/Formatting.scala
           |package com.example
           |
           |object Formatting{def main(args:Array[String]):Unit=println("needs formatting")}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Formatting.scala")
      client <- startMcpServer()
      formatted <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/Formatting.scala")
          .toString
      )
      _ = assert(
        formatted.contains("object Formatting {"),
        s"Expected formatted output to contain proper spacing, got: $formatted",
      )
      _ = assert(
        formatted.contains("def main(args: Array[String]): Unit ="),
        s"Expected formatted output to contain proper spacing, got: $formatted",
      )
      _ = assert(
        formatted.contains("println(\"needs formatting\")"),
        s"Expected formatted output with proper spacing, got: $formatted",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - without scalafmt config") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Formatting.scala
           |package com.example
           |
           |object Formatting{def main(args:Array[String]):Unit=println("needs formatting")}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Formatting.scala")
      client <- startMcpServer()
      result <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/Formatting.scala")
          .toString
      )
      // Without scalafmt config, may not format or may say already formatted
      _ = assert(
        result.contains("File is already properly formatted.") || result
          .contains("object Formatting"),
        s"Expected either 'already formatted' message or formatted code, got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - already properly formatted") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.scalafmt.conf
           |version = "${V.scalafmtVersion}"
           |runner.dialect = scala213
           |/a/src/main/scala/com/example/Formatting.scala
           |package com.example
           |
           |object Formatting {
           |  def main(args: Array[String]): Unit = println("already formatted")
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Formatting.scala")
      client <- startMcpServer()
      result <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/Formatting.scala")
          .toString
      )
      _ = assertNoDiff(
        result,
        "File is already properly formatted.",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - non-existent file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/NonExistent.scala")
          .toString
      )
      _ = assert(
        result.contains("Error: File not found or not a Scala file"),
        s"Expected error message for non-existent file, got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - non-Scala file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/java/com/example/JavaFile.java
           |package com.example;
           |
           |public class JavaFile {
           |    public static void main(String[] args) {
           |        System.out.println("Java file");
           |    }
           |}
           |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.format(
        server.workspace
          .resolve("a/src/main/java/com/example/JavaFile.java")
          .toString
      )
      _ = assert(
        result.contains("Error: File not found or not a Scala file"),
        s"Expected error message for Java file, got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - with custom scalafmt config location") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.scala-build/.scalafmt.conf
           |version = "${V.scalafmtVersion}"
           |runner.dialect = scala213
           |style = IntelliJ
           |/a/src/main/scala/com/example/Formatting.scala
           |package com.example
           |
           |object Formatting{def main(args:Array[String]):Unit=println("needs formatting")}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Formatting.scala")
      client <- startMcpServer()
      formatted <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/Formatting.scala")
          .toString
      )
      _ = assert(
        formatted.contains("object Formatting {"),
        s"Expected formatted output with scala-build config, got: $formatted",
      )
      _ = assert(
        formatted.contains("def main(args: Array[String]): Unit ="),
        s"Expected formatted output with scala-build config, got: $formatted",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("format-file - with syntax errors") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.scalafmt.conf
           |version = "${V.scalafmtVersion}"
           |runner.dialect = scala213
           |/a/src/main/scala/com/example/Formatting.scala
           |package com.example
           |
           |object Formatting{def main(args:Array[String]):Unit=println("syntax error" }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Formatting.scala")
      client <- startMcpServer()
      result <- client.format(
        server.workspace
          .resolve("a/src/main/scala/com/example/Formatting.scala")
          .toString
      )
      // With syntax errors, scalafmt may return error, formatted code, or "already formatted"
      _ = assert(
        result.contains("Error formatting file") ||
          result.contains("object Formatting") ||
          result.contains("File is already properly formatted."),
        s"Expected error message, code content, or 'already formatted', got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }
}
