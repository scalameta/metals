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
      _ = assertNoDiff(
        formatted,
        s"""
           |package com.example
           |
           |object Formatting {
           |  def main(args: Array[String]): Unit =
           |    println("needs formatting")
           |}
           |""".stripMargin,
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
      _ = assertNoDiff(
        result,
        """package com.example
          |
          |object Formatting {
          |  def main(args: Array[String]): Unit = println("needs formatting")
          |}
          |""".stripMargin,
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
      _ = assertNoDiff(
        formatted,
        s"""
           |package com.example
           |
           |object Formatting {
           |  def main(args: Array[String]): Unit = println("needs formatting")
           |}
           |""".stripMargin,
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
           |object Formatting{def main(args:Array[String]):Unit=println("syntax error"}
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
        "Formatting error: Format error",
      )
      _ <- client.shutdown()
    } yield ()
  }
}
