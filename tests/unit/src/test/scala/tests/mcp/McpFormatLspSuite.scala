package tests.mcp

import java.nio.file.Files

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourceItemKind
import tests.BaseLspSuite

class McpFormatLspSuite extends BaseLspSuite("mcp-format") with McpTestUtils {

  test("with-scalafmt-config") {
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
        "a/src/main/scala/com/example/Formatting.scala was formatted",
      )
      fileContenxt = server.buffers
        .get(workspace.resolve("a/src/main/scala/com/example/Formatting.scala"))
        .mkString
      _ = assertNoDiff(
        fileContenxt,
        """|package com.example
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

  test("without-scalafmt-config") {
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
        "a/src/main/scala/com/example/Formatting.scala was formatted",
      )
      fileContenxt = server.buffers
        .get(workspace.resolve("a/src/main/scala/com/example/Formatting.scala"))
        .mkString
      _ = assertNoDiff(
        fileContenxt,
        """|package com.example
           |
           |object Formatting {
           |  def main(args: Array[String]): Unit = println("needs formatting")
           |}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("already-properly-formatted") {
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

  test("non-existent-file") {
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

  test("non-scala-file") {
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

  test("with-custom-scalafmt-config-location") {
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
        "a/src/main/scala/com/example/Formatting.scala was formatted",
      )
      fileContenxt = server.buffers
        .get(workspace.resolve("a/src/main/scala/com/example/Formatting.scala"))
        .mkString
      _ = assertNoDiff(
        fileContenxt,
        """|package com.example
           |
           |object Formatting {
           |  def main(args: Array[String]): Unit = println("needs formatting")
           |}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("with-syntax-errors") {
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

  test("multiple-files") {
    cleanWorkspace()
    val first = "a/src/main/scala/com/example/First.scala"
    val firstAlias = "a/src/main/scala/com/example/./First.scala"
    val second = "a/src/main/scala/com/example/Second.scala"
    val unchanged = "a/src/main/scala/com/example/Unchanged.scala"
    val excluded = "a/src/main/scala/com/example/Excluded.scala"
    val broken = "a/src/main/scala/com/example/Broken.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = ${V.scalafmtVersion}
            |runner.dialect = scala213
            |project.excludeFilters = [
            |  ".*Excluded.scala$$"
            |]
            |/metals.json
            |{"a": {}}
            |/$first
            |object First {
            | val value = 1  }
            |/$second
            |object Second {
            | val value = 2  }
            |/$unchanged
            |object Unchanged {
            |  val value = 3
            |}
            |
            |/$excluded
            |object Excluded {
            | val value = 4  }
            |/$broken
            |object Broken{def main(args:Array[String]):Unit=println("syntax error"}
            |""".stripMargin
      )
      _ <- server.didOpen(first)
      _ <- server.didOpen(second)
      _ <- server.didOpen(unchanged)
      _ <- server.didOpen(excluded)
      _ <- server.didOpen(broken)
      client <- startMcpServer()
      result <- client.formatFiles(
        List(first, firstAlias, second, first, unchanged, excluded, broken)
      )
      // Named paths always try to format; Scalafmt still leaves exclude-filter
      // matches unchanged, so they count as unchanged rather than excluded.
      _ = assertNoDiff(
        result,
        s"""|Format summary: formatted 2, unchanged 2, excluded 0, errors 1.
            |
            |Errors:
            |- $broken: Formatting error: Format error
            |""".stripMargin.trim,
      )
      _ = assertNoDiff(
        server.bufferContents(first),
        """|object First {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        server.bufferContents(second),
        """|object Second {
           |  val value = 2
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        server.bufferContents(excluded),
        """|object Excluded {
           | val value = 4  }
           |""".stripMargin,
      )
      _ = assertNoDiff(
        server.bufferContents(broken),
        """|object Broken{def main(args:Array[String]):Unit=println("syntax error"}
           |""".stripMargin,
      )
      excludedResult <- client.formatFile(excluded)
      _ = assertNoDiff(
        excludedResult,
        "File is already properly formatted.",
      )
      // Discovered scopes honor project exclude filters.
      moduleResult <- client.formatModule("a")
      _ = assertNoDiff(
        moduleResult,
        s"""|Format summary: formatted 0, unchanged 3, excluded 1, errors 1.
            |
            |Errors:
            |- $broken: Formatting error: Format error
            |""".stripMargin.trim,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("absolute-file-outside-workspace") {
    cleanWorkspace()
    val outside = Files.createTempFile("mcp-format", ".scala").toRealPath()
    val outsidePath = AbsolutePath(outside)
    val source = "object Outside{val value=1}"
    Files.writeString(outside, source)
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = ${V.scalafmtVersion}
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |""".stripMargin
      )
      _ = server.buffers.put(outsidePath, source, 0)
      client <- startMcpServer()
      result <- client.formatFiles(List(outside.toString))
      _ = assertNoDiff(
        result,
        "Format summary: formatted 1, unchanged 0, excluded 0, errors 0.",
      )
      _ = assertNoDiff(
        server.buffers.get(outsidePath).getOrElse(fail("missing buffer")),
        "object Outside { val value = 1 }\n",
      )
      _ <- client.shutdown()
      _ = Files.deleteIfExists(outside)
    } yield ()
  }

  test("module-and-workspace") {
    cleanWorkspace()
    val inA = "a/src/main/scala/com/example/InA.scala"
    val inB = "b/src/main/scala/com/example/InB.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = ${V.scalafmtVersion}
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}, "b": {}, "empty": {}}
            |/$inA
            |object InA {
            | val value = 1  }
            |/$inB
            |object InB {
            | val value = 2  }
            |""".stripMargin
      )
      _ <- server.didOpen(inA)
      _ <- server.didOpen(inB)
      client <- startMcpServer()
      moduleResult <- client.formatModule("a")
      _ = assertNoDiff(
        moduleResult,
        "Format summary: formatted 1, unchanged 0, excluded 0, errors 0.",
      )
      _ = assertNoDiff(
        server.bufferContents(inA),
        """|object InA {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        server.bufferContents(inB),
        """|object InB {
           | val value = 2  }
           |""".stripMargin,
      )
      workspaceResult <- client.formatAll()
      _ = assertNoDiff(
        workspaceResult,
        "Format summary: formatted 1, unchanged 1, excluded 0, errors 0.",
      )
      _ = assertNoDiff(
        server.bufferContents(inB),
        """|object InB {
           |  val value = 2
           |}
           |""".stripMargin,
      )
      emptyModule <- client.formatModule("empty")
      _ = assertNoDiff(
        emptyModule,
        "Format summary: formatted 0, unchanged 0, excluded 0, errors 0.",
      )
      missingModule <- client.formatModule("nope")
      _ = assertNoDiff(
        missingModule,
        "Error: Module not found: nope, see `list-modules`.",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("selectors-are-exclusive") {
    cleanWorkspace()
    val focused = "a/src/main/scala/com/example/Focused.scala"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$focused
            |object Focused {
            | val value = 1  }
            |""".stripMargin
      )
      _ <- server.didOpen(focused)
      client <- startMcpServer()
      conflict <- client.formatWithSelectors(
        files = Some(List(focused)),
        module = Some("a"),
      )
      _ = assertNoDiff(
        conflict,
        "Error: set only one of `files`, `module` or `all`.",
      )
      emptyResult <- client.formatFiles(Nil)
      _ = assertNoDiff(
        emptyResult,
        "Error: `files` must contain at least one non-empty path.",
      )
      blankResult <- client.formatFiles(List(""))
      _ = assertNoDiff(
        blankResult,
        "Error: `files` must contain at least one non-empty path.",
      )
      _ = assertNoDiff(
        server.bufferContents(focused),
        """|object Focused {
           | val value = 1  }
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("skips-generated-sources") {
    cleanWorkspace()
    val regular = "a/src/main/scala/com/example/Regular.scala"
    val generatedDirectory = "a/src/main/scala/generated"
    val generated = s"$generatedDirectory/Generated.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = ${V.scalafmtVersion}
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$regular
            |object Regular {
            | val value = 1  }
            |/$generated
            |object Generated {
            | val value = 2  }
            |""".stripMargin
      )
      _ = {
        val buildTargets = server.server.buildTargets
        val target = buildTargets.allScala
          .find(_.displayName == "a")
          .getOrElse(fail("missing build target a"))
        val targetData = buildTargets
          .targetData(target.id)
          .getOrElse(fail("missing target data for a"))
        targetData.addSourceItem(
          new SourceItem(
            workspace.resolve(generatedDirectory).toURI.toString,
            SourceItemKind.DIRECTORY,
            true,
          ),
          target.id,
        )
      }
      _ <- server.didOpen(regular)
      _ <- server.didOpen(generated)
      client <- startMcpServer()
      moduleResult <- client.formatModule("a")
      _ = assertNoDiff(
        moduleResult,
        "Format summary: formatted 1, unchanged 0, excluded 0, errors 0.",
      )
      _ = assertNoDiff(
        server.bufferContents(generated),
        """|object Generated {
           | val value = 2  }
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }
}
