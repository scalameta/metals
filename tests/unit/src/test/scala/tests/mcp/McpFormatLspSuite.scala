package tests.mcp

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class McpFormatLspSuite extends BaseLspSuite("mcp-format") with McpTestUtils {

  private val success = "Scalafmt completed successfully."

  private def diskText(path: String): String =
    workspace.resolve(path).readText

  test("files-relative-and-absolute") {
    cleanWorkspace()
    val first = "a/src/main/scala/com/example/First.scala"
    val second = "a/src/main/scala/com/example/Second.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$first
            |object First {
            | val value = 1  }
            |/$second
            |object Second {
            | val value = 2  }
            |""".stripMargin
      )
      client <- startMcpServer()
      absoluteFirst = workspace.resolve(first).toString
      result <- client.formatFiles(List(first, absoluteFirst, second))
      _ = assertNoDiff(result, success)
      _ = assertNoDiff(
        diskText(first),
        """|object First {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        diskText(second),
        """|object Second {
           |  val value = 2
           |}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("absolute-file-outside-workspace") {
    cleanWorkspace()
    val outside = Files.createTempFile("mcp-format", ".scala").toRealPath()
    Files.writeString(outside, "object Outside{val value=1}")
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatFiles(List(outside.toString))
      _ = assertNoDiff(result, success)
      _ = assertNoDiff(
        Files.readString(outside),
        "object Outside { val value = 1 }\n",
      )
      _ <- client.shutdown()
      _ = Files.deleteIfExists(outside)
    } yield ()
  }

  test("all") {
    cleanWorkspace()
    val inA = "a/src/main/scala/com/example/InA.scala"
    val inB = "b/src/main/scala/com/example/InB.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}, "b": {}}
            |/$inA
            |object InA {
            | val value = 1  }
            |/$inB
            |object InB {
            | val value = 2  }
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatAll()
      _ = assertNoDiff(result, success)
      _ = assertNoDiff(
        diskText(inA),
        """|object InA {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        diskText(inB),
        """|object InB {
           |  val value = 2
           |}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("all-skips-nested-target-directories") {
    cleanWorkspace()
    val included = "a/src/main/scala/targeted/Included.scala"
    val generated = "tests/unit/target/generated/Generated.scala"
    val unformatted = "object Generated{val value=2}\n"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$included
            |object Included{val value=1}
            |/$generated
            |$unformatted""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatAll()
      _ = assertNoDiff(result, success)
      _ = assertNoDiff(
        diskText(included),
        "object Included { val value = 1 }\n",
      )
      _ = assertNoDiff(diskText(generated), unformatted)
      explicit <- client.formatFiles(List(generated))
      _ = assertNoDiff(explicit, success)
      _ = assertNoDiff(
        diskText(generated),
        "object Generated { val value = 2 }\n",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("exclude-filters") {
    cleanWorkspace()
    val included = "a/src/main/scala/com/example/Included.scala"
    val excluded = "a/src/main/scala/com/example/Excluded.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |project.excludeFilters = [
            |  ".*Excluded.scala$$"
            |]
            |/metals.json
            |{"a": {}}
            |/$included
            |object Included {
            | val value = 1  }
            |/$excluded
            |object Excluded {
            | val value = 2  }
            |""".stripMargin
      )
      client <- startMcpServer()
      explicit <- client.formatFiles(List(included, excluded))
      _ = assertNoDiff(explicit, success)
      _ = assertNoDiff(
        diskText(included),
        """|object Included {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        diskText(excluded),
        """|object Excluded {
           |  val value = 2
           |}
           |""".stripMargin,
      )
      _ = workspace
        .resolve(included)
        .writeText(
          """|object Included {
             | val value = 1  }
             |""".stripMargin
        )
      _ = workspace
        .resolve(excluded)
        .writeText(
          """|object Excluded {
             | val value = 2  }
             |""".stripMargin
        )
      allResult <- client.formatAll()
      _ = assertNoDiff(allResult, success)
      _ = assertNoDiff(
        diskText(included),
        """|object Included {
           |  val value = 1
           |}
           |""".stripMargin,
      )
      _ = assertNoDiff(
        diskText(excluded),
        """|object Excluded {
           | val value = 2  }
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("all-matches-no-files") {
    cleanWorkspace()
    val excluded = "a/src/main/scala/com/example/Excluded.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |project.excludeFilters = [
            |  ".*"
            |]
            |/metals.json
            |{"a": {}}
            |/$excluded
            |object Excluded {
            | val value = 1  }
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatAll()
      _ = assert(result.startsWith("Scalafmt failed:"), result)
      _ = assert(
        result.contains("No files formatted"),
        s"Expected No files formatted, got: $result",
      )
      _ = assertNoDiff(
        diskText(excluded),
        """|object Excluded {
           | val value = 1  }
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("syntax-error") {
    cleanWorkspace()
    val path = "a/src/main/scala/com/example/Broken.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$path
            |object Broken{def main(args:Array[String]):Unit=println("syntax error"}
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatFiles(List(path))
      _ = assert(result.startsWith("Scalafmt failed:"), result)
      _ = assert(result.contains(path) || result.contains("error"), result)
      _ = assertNoDiff(
        diskText(path),
        """|object Broken{def main(args:Array[String]):Unit=println("syntax error"}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("two-syntax-errors") {
    cleanWorkspace()
    val first = "a/src/main/scala/com/example/BrokenOne.scala"
    val second = "a/src/main/scala/com/example/BrokenTwo.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$first
            |object BrokenOne{def main(args:Array[String]):Unit=println("syntax error"}
            |/$second
            |object BrokenTwo{def main(args:Array[String]):Unit=println("syntax error"}
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatFiles(List(first, second))
      _ = assert(result.startsWith("Scalafmt failed:"), result)
      _ = assert(result.contains("BrokenOne"), result)
      _ = assert(result.contains("BrokenTwo"), result)
      _ <- client.shutdown()
    } yield ()
  }

  test("missing-path") {
    cleanWorkspace()
    val missing = "a/src/main/scala/com/example/Missing.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatFiles(List(missing))
      _ = assertNoDiff(result, s"Error: files not found: $missing")
      _ <- client.shutdown()
    } yield ()
  }

  test("already-formatted") {
    cleanWorkspace()
    val path = "a/src/main/scala/com/example/Formatted.scala"
    val source =
      """|object Formatted {
         |  val value = 1
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$path
            |$source""".stripMargin
      )
      client <- startMcpServer()
      result <- client.formatFiles(List(path))
      _ = assertNoDiff(result, success)
      _ = assertNoDiff(diskText(path), source)
      _ <- client.shutdown()
    } yield ()
  }

  test("selectors") {
    cleanWorkspace()
    val path = "a/src/main/scala/com/example/Focused.scala"
    for {
      _ <- initialize(
        s"""|/.scalafmt.conf
            |version = "${V.scalafmtVersion}"
            |runner.dialect = scala213
            |/metals.json
            |{"a": {}}
            |/$path
            |object Focused {
            | val value = 1  }
            |""".stripMargin
      )
      client <- startMcpServer()
      missing <- client.formatWithSelectors()
      _ = assertNoDiff(missing, "Error: set either `files` or `all`.")
      conflict <- client.formatWithSelectors(
        files = Some(List(path)),
        all = true,
      )
      _ = assertNoDiff(conflict, "Error: set only one of `files` or `all`.")
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
        diskText(path),
        """|object Focused {
           | val value = 1  }
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }
}
