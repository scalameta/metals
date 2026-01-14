package tests.mcp

import scala.concurrent.Future

import scala.meta.internal.metals.mcp.CursorEditor
import scala.meta.internal.metals.mcp.McpConfig
import scala.meta.internal.metals.mcp.McpMessages
import scala.meta.internal.metals.mcp.MetalsMcpServer
import scala.meta.internal.metals.mcp.NoClient
import scala.meta.internal.metals.mcp.SymbolType

import munit.Location
import tests.BaseLspSuite

class McpServerLspSuite extends BaseLspSuite("mcp-server") with McpTestUtils {

  test("find-dep") {
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

  def checkScalafixRule(
      testName: String,
      input: String,
      expectedMessage: String,
      expectedRules: String,
      ruleName: String,
      reverseRule: Option[String => String] = None,
  )(implicit loc: Location): Unit = {
    test(testName) {
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
          input,
          "Replaces John with Johnatan",
        )
        _ = assertNoDiff(
          result,
          expectedMessage,
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
          expectedRules,
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
        _ <- client.runScalafixRule(ruleName)
        _ = assertNoDiff(
          server.buffers
            .get(workspace.resolve("a/src/main/scala/com/example/Hello.scala"))
            .get,
          expectedResult,
        )
        _ <- reverseRule match {
          case Some(function) =>
            for {
              _ <- client.generateScalafixRule(
                function(input),
                "Replaces John with Johnatan",
              )
              _ = assertNoDiff(
                server.buffers
                  .get(
                    workspace.resolve(
                      "a/src/main/scala/com/example/Hello.scala"
                    )
                  )
                  .get,
                initial,
              )
            } yield ()
          case None => Future.successful(())
        }

        _ <- client.shutdown()
      } yield ()
    }
  }

  checkScalafixRule(
    "replace-john-with-johnatan-simple",
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
    """|Created and ran Scalafix rule ReplaceJohnWithJohnatan successfully
       |""".stripMargin,
    """|Available scalafix rules:
       |- ExplicitResultTypes: Inserts type annotations for inferred public members.
       |- OrganizeImports: Organize import statements, used for source.organizeImports code action
       |- ProcedureSyntax: Replaces deprecated Scala 2.x procedure syntax with explicit ': Unit ='
       |- RedundantSyntax: Removes redundant syntax such as `final` modifiers on an object
       |- RemoveUnused: Removes unused imports and terms that reported by the compiler under -Wunused
       |- ReplaceJohnWithJohnatan: Replaces John with Johnatan
       |""".stripMargin,
    "ReplaceJohnWithJohnatan",
    Some(input =>
      input.replace(
        "val newValue = value.replace(\"John\", \"\\\"Johnatan\\\"\")",
        "val newValue = value.replace(\"Johnatan\", \"\\\"John\\\"\")",
      )
    ),
  )

  checkScalafixRule(
    "replace-john-with-johnatan-mismatch",
    """|
       |package fix
       |
       |import scalafix.v1._
       |import scala.meta._
       |
       |class ReplacerClass extends SemanticRule("Replacer") {
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
    """|Created and ran Scalafix rule Replacer successfully
       |""".stripMargin,
    """|Available scalafix rules:
       |- ExplicitResultTypes: Inserts type annotations for inferred public members.
       |- OrganizeImports: Organize import statements, used for source.organizeImports code action
       |- ProcedureSyntax: Replaces deprecated Scala 2.x procedure syntax with explicit ': Unit ='
       |- RedundantSyntax: Removes redundant syntax such as `final` modifiers on an object
       |- RemoveUnused: Removes unused imports and terms that reported by the compiler under -Wunused
       |- Replacer: Replaces John with Johnatan
       |""".stripMargin,
    "Replacer",
  )

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
      path = RelativePath("a/src/main/scala/com/example/Hello.scala")
      _ = assertNoDiff(
        result,
        s"""|Error: No changes were made for rule ReplaceJohnWithJohnatan
            |Files tried:
            |$path
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
      McpConfig.readPort(
        server.server.folder,
        server.server.getVisibleName,
        CursorEditor,
      ),
      None,
    )
    assert(
      McpConfig
        .readPort(server.server.folder, server.server.getVisibleName, NoClient)
        .isDefined,
      "MCP server port should be defined in the default location",
    )
  }

}
