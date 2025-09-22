package tests.mcp

import scala.meta.internal.metals.mcp.CursorEditor
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.McpMessages
import scala.meta.internal.metals.mcp.NoClient
import scala.meta.internal.metals.mcp.SymbolType

import tests.BaseLspSuite

class McpServerLspSuite extends BaseLspSuite("mcp-server") with McpTestUtils {

  // get a new random port that is not in use
  val portToUse: Int = {
    val socket = new java.net.ServerSocket(0)
    val port = socket.getLocalPort()
    socket.close()
    port
  }

  test("find-dep") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/.metals/mcp.json
           |{
           |  "servers": {
           |    "root-metals": {
           |      "url": "http://localhost:${portToUse}/sse"
           |    }
           |  }
           |}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      _ = assertEquals(client.port, portToUse)
      result <- client.findDep("org.scala-lan")
      _ = assertNoDiff(
        result.mkString("\n"),
        McpMessages.FindDep.dependencyReturnMessage(
          "organization",
          Seq("org.scala-lang", "org.scala-lang-osgi"),
        ),
      )
      resultName <- client.findDep("org.scala-lang", Some("scala-librar"))
      _ = assertNoDiff(
        resultName.mkString("\n"),
        McpMessages.FindDep.dependencyReturnMessage(
          "name",
          Seq("scala-library", "scala-library-all"),
        ),
      )
      resultNameVersion <- client.findDep(
        "org.scalameta",
        Some("parsers"),
        Some("4.10."),
      )
      _ = assertNoDiff(
        resultNameVersion.mkString("\n"),
        McpMessages.FindDep
          .dependencyReturnMessage("version", Seq("4.10.2", "4.10.1", "4.10.0")),
      )
      resultNameVersion2 <- client.findDep(
        "org.scalameta",
        Some("parsers_2.13"),
        None,
      )
      _ = assert(
        resultNameVersion2.head.contains(", 4.13.0,"),
        s"Expected to contain version 4.13.0, got ${resultNameVersion2.mkString("\n")}",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("list-modules") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      modules <- client.listModules()
      _ = assertNoDiff(
        modules,
        """|Available modules (build targets):
           |- a
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("run-scalafix-rule") {
    cleanWorkspace()
    val initial =
      """|package com.example
         |
         |object Hello { 
         |  val str = "John"
         |  def main(args: Array[String]): Unit = println(str)
         |}
         |""".stripMargin
    val expectedResult =
      """|package com.example
         |
         |object Hello { 
         |  val str = "Johnatan"
         |  def main(args: Array[String]): Unit = println(str)
         |}
         |""".stripMargin
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |$initial
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      result <- client.generateScalafixRule(
        "ReplaceJohnWithJohnatan",
        """|
           |package fix
           |
           |import scalafix.v1._
           |import scala.meta._
           |
           |class ReplaceJohnWithJohnatan extends SemanticRule("ReplaceJohnWithJohnatan") {
           |  override def fix(implicit doc: SemanticDocument): Patch = {
           |    doc.tree.collect {
           |      case lit @ Lit.String(value) if value.contains("John") =>
           |        val newValue = value.replace("John", "\"Johnatan\"")
           |        Patch.replaceTree(lit, newValue)
           |    }.asPatch
           |  }
           |}
           |
           |""".stripMargin,
        "Replaces John with Johnatan",
      )
      _ = assertNoDiff(
        result,
        """|Created and ran Scalafix rule ReplaceJohnWithJohnatan successfully
           |""".stripMargin,
      )
      _ = assertNoDiff(
        server.buffers
          .get(workspace.resolve("a/src/main/scala/com/example/Hello.scala"))
          .get,
        expectedResult,
      )
      // Test listing scalafix rules after creating one
      rules <- client.listScalafixRules()
      _ = assertNoDiff(
        rules,
        """|Available scalafix rules:
           |- ExplicitResultTypes: Inserts type annotations for inferred public members.
           |- OrganizeImports: Organize import statements, used for source.organizeImports code action
           |- ProcedureSyntax: Replaces deprecated Scala 2.x procedure syntax with explicit ': Unit ='
           |- RedundantSyntax: Removes redundant syntax such as `final` modifiers on an object
           |- RemoveUnused: Removes unused imports and terms that reported by the compiler under -Wunused
           |- ReplaceJohnWithJohnatan: Replaces John with Johnatan
           |""".stripMargin,
      )
      _ <- server.didChange("a/src/main/scala/com/example/Hello.scala") {
        _.replace("Johnatan", "John")
      }
      _ <- server.didSave("a/src/main/scala/com/example/Hello.scala")
      _ = assertNoDiff(
        server.buffers
          .get(workspace.resolve("a/src/main/scala/com/example/Hello.scala"))
          .get,
        initial,
      )
      _ <- client.runScalafixRule("ReplaceJohnWithJohnatan")
      _ = assertNoDiff(
        server.buffers
          .get(workspace.resolve("a/src/main/scala/com/example/Hello.scala"))
          .get,
        expectedResult,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("list-scalafix-rules") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      // Test with no rules present
      noRulesResult <- client.listScalafixRules()
      _ = assertNoDiff(
        noRulesResult,
        """|Available scalafix rules:
           |- ExplicitResultTypes: Inserts type annotations for inferred public members.
           |- OrganizeImports: Organize import statements, used for source.organizeImports code action
           |- ProcedureSyntax: Replaces deprecated Scala 2.x procedure syntax with explicit ': Unit ='
           |- RedundantSyntax: Removes redundant syntax such as `final` modifiers on an object
           |- RemoveUnused: Removes unused imports and terms that reported by the compiler under -Wunused
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("generate-scalafix-rule-error") {
    import scala.meta._
    cleanWorkspace()
    val sampleCode = "a.filter(x => x > 10).map(x => x + 1)"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      // Test with no rules present
      result <- client.generateScalafixRule(
        "ReplaceJohnWithJohnatan",
        """|
           |package fix
           |
           |import scalafix.v1._
           |import scala.meta._
           |
           |class ReplaceJohnWithJohnatan extends SemanticRule("ReplaceJohnWithJohnatan") {
           |  override def fix(implicit doc: SemanticDocument): Patch = {
           |    doc.tree.collect {
           |      case lit @ Lit.String(value) if value.contains("Doesn't exist") =>
           |        val newValue = value.replace("John", "\"Johnatan\"")
           |        Patch.replaceTree(lit, newValue)
           |    }.asPatch
           |  }
           |}
           |
           |""".stripMargin,
        "Converts John to Johnatan",
        Some(sampleCode),
      )
      _ = assertNoDiff(
        result,
        s"""|Error: No changes were made for rule ReplaceJohnWithJohnatan
            |Sample code structure: ${sampleCode.parse[Stat].get.structure}
            |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("typed-glob-search-valid") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      result <- client.typedGlobSearch("Hello", List("class", "object"))
      _ = assert(
        result.contains("com.example.Hello"),
        s"Expected to find Hello object, got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("typed-glob-search-invalid-symbol-types") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      result <- client.typedGlobSearch(
        "Hello",
        List("invalid_type", "another_invalid"),
      )
      validTypes = SymbolType.values.map(_.name).mkString(", ")
      expectedError = s"Error: Invalid symbol types: invalid_type, another_invalid. Valid types are: $validTypes, arguments: " +
        """{"query":"Hello","symbolType":["invalid_type","another_invalid"]}"""
      _ = assertNoDiff(result, expectedError)
      _ <- client.shutdown()
    } yield ()
  }

  test("typed-glob-search-string-array-format") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      result <- client.typedGlobSearch("Hello", """["class", "object"]""")
      _ = assert(
        result.contains("com.example.Hello"),
        s"Expected to find Hello object, got: $result",
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("typed-glob-search-invalid-string-array") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello { def main(args: Array[String]): Unit = println("Hello") }
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      result <- client.typedGlobSearch("Hello", """invalid_json""")
      _ = assertNoDiff(
        result,
        """|Error: Incorrect argument type for symbolType, expected: Array[String], arguments: {"query":"Hello","symbolType":"invalid_json"}
           |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  override def afterEach(context: AfterEach): Unit = {
    super.afterEach(context)
    assertEquals(
      McpConfig.readPort(server.workspace, "root", CursorEditor),
      None,
    )
    assert(
      McpConfig.readPort(server.workspace, "root", NoClient).isDefined,
      "MCP server port should be defined in the default location",
    )
  }

}
