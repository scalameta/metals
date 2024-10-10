package tests.scalafix

import scala.concurrent.Future

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalafixProvider
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import tests.BaseLspSuite

class ScalafixProviderLspSuite extends BaseLspSuite("scalafix-provider") {

  def scalafixConf(path: String = "/.scalafix.conf"): String =
    s"""|$path
        |rules = [
        |  OrganizeImports,
        |  ExplicitResultTypes,
        |  RemoveUnused
        |]
        |
        |ExplicitResultTypes.rewriteStructuralTypesToNamedSubclass = false
        |
        |RemoveUnused.imports = false
        |
        |OrganizeImports.groupedImports = Explode
        |OrganizeImports.expandRelative = true
        |OrganizeImports.removeUnused = true
        |OrganizeImports.groups = [
        |  "scala."
        |  "re:javax?\\\\."
        |  "*"
        |]
        |
        |""".stripMargin

  test("base-all") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalacOptions": ["-Wunused"] }}
           |${scalafixConf()}
           |/a/src/main/scala/Main.scala
           | // remove this import
           |import java.io.File
           |class A
           |object Main{
           |  def debug { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
           |  val addTypeHere = new A{}
           |  private val notUsed = 123 
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        // we need warnings for scalafix rules
        client.workspaceDiagnostics,
        """|a/src/main/scala/Main.scala:2:1: warning: Unused import
           |import java.io.File
           |^^^^^^^^^^^^^^^^^^^
           |a/src/main/scala/Main.scala:7:15: warning: private val notUsed in object Main is never used
           |  private val notUsed = 123 
           |              ^^^^^^^
           |""".stripMargin,
      )
      textParams =
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            workspace.resolve("a/src/main/scala/Main.scala").toURI.toString()
          ),
          new Position(0, 0),
        )
      // run the actual scalafix command
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      contents = server.bufferContents("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        contents,
        """|// remove this import
           |
           |class A
           |object Main{
           |  def debug { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
           |  val addTypeHere: A = new A{}
           |   
           |}
           |""".stripMargin,
      )
      // add a new rule to scalafix configuration
      _ <- server.didSave(".scalafix.conf") { old =>
        old.replace(
          "ExplicitResultTypes,",
          "ExplicitResultTypes,\n  ProcedureSyntax,",
        )
      }
      // execute the scalafix command again
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      contentsAfterConfigChange = server.bufferContents(
        "a/src/main/scala/Main.scala"
      )
      // make sure that the newly added rule works
      _ = assertNoDiff(
        contentsAfterConfigChange,
        """|// remove this import
           |
           |class A
           |object Main{
           |  def debug: Unit = { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
           |  val addTypeHere: A = new A{}
           |   
           |}
           |""".stripMargin,
      ),

    } yield ()
  }

  // make sure that a proper warning is show for unknown rules
  test("no-rule") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalacOptions": ["-Wunused"] }}
           |/.scalafix.conf
           |rules = [
           |  NotARule,
           |  NotARule2
           |]
           |
           |/a/src/main/scala/Main.scala
           |object Main{
           |  val addTypeHere = new A{}
           |  private val notUsed = 123 
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      textParams =
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            workspace.resolve("a/src/main/scala/Main.scala").toURI.toString()
          ),
          new Position(0, 0),
        )
      _ <- server
        .executeCommand(
          ServerCommands.RunScalafix,
          textParams,
        )
        .recoverWith { case ScalafixProvider.ScalafixRunException(_) =>
          Future.unit
        }
      _ = assertNoDiff(
        client.workspaceShowMessages,
        "Metals is unable to run NotARule. Please add the rule dependency to `metals.scalafixRulesDependencies`.",
      )
      contents = server.bufferContents("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        contents,
        """|object Main{
           |  val addTypeHere = new A{}
           |  private val notUsed = 123 
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  // add a rule in the settings and test if it works
  test("custom-rule") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalacOptions": ["-Wunused"] }}
           |${scalafixConf()}
           |/a/src/main/scala/Main.scala
           |class A
           |object Main{
           |  private val notUsed = 123 
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      textParams =
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            workspace.resolve("a/src/main/scala/Main.scala").toURI.toString()
          ),
          new Position(0, 0),
        )
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      contents = server.bufferContents("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        contents,
        """|
           |class A
           |object Main{
           |   
           |}
           |""".stripMargin,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "scalafix-rules-dependencies": [
          |    "ch.epfl.scala::example-scalafix-rule:1.4.0"
          |  ]
          |}
          |""".stripMargin
      )
      _ <- server.didSave(".scalafix.conf") { old =>
        old.replace(
          "ExplicitResultTypes,",
          "ExplicitResultTypes,\n   \"class:fix.Examplescalafixrule_v1\",",
        )
      }
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      contentsAfterConfigChange = server.bufferContents(
        "a/src/main/scala/Main.scala"
      )
      _ = assertNoDiff(
        contentsAfterConfigChange,
        """|class A
           |object Main{
           |   
           |}
           |// Hello world!
           |""".stripMargin,
      )
    } yield ()
  }

  // Testing one of each contributed dependency
  test("contrib") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{"scalacOptions": ["-Wunused"] }}
           |/.scalafix.conf
           |rules = [
           |  EmptyCollectionsUnified,
           |  UseNamedParameters,
           |  MissingFinal,
           |  RemoveEmptyObject,
           |  ZeroIndexToHead
           |]
           |
           |/a/src/main/scala/Main.scala
           |object RemoveMe
           |object A {
           |  case class Bar(i: Int)
           |  def func(a: Int, b: Int, c: Int) = a + b + c 
           |}
           |object Main{
           |  val used = List()
           |  val used2 = List(1)
           |  used2(0)
           |  A.func(1, 2, 3)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      textParams =
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            workspace.resolve("a/src/main/scala/Main.scala").toURI.toString()
          ),
          new Position(0, 0),
        )
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      contents = server.bufferContents("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        contents,
        """|object A {
           |  final case class Bar(i: Int)
           |  def func(a: Int, b: Int, c: Int) = a + b + c 
           |}
           |object Main{
           |  val used = List.empty
           |  val used2 = List(1)
           |  used2.head
           |  A.func(a = 1, b = 2, c = 3)
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("amend-scalafix-conf") {
    cleanWorkspace()
    val newSettings = List(
      "OrganizeImports.targetDialect = Scala3"
    )
    val amendScalafixConfRequest = Messages.ScalafixConfig.amendRequest(
      newSettings,
      V.scala3,
      isScala3Source = false,
    )

    client.showMessageRequestHandler = params =>
      if (params.getMessage() == amendScalafixConfRequest.getMessage())
        Some(Messages.ScalafixConfig.adjustScalafix)
      else {
        scribe.error(s"Unexpected message: ${params.getMessage()}")
        None
      }

    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a":{"scalaVersion": "${V.scala3}" }}
            |/a/src/main/scala/Main.scala
            |import java.util.concurrent.*
            |import java.util.*
            |/.scalafix.conf
            |rules = [
            |  OrganizeImports
            |]
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      textParams =
        new TextDocumentPositionParams(
          new TextDocumentIdentifier(
            workspace.resolve("a/src/main/scala/Main.scala").toURI.toString()
          ),
          new Position(0, 0),
        )
      _ <- server.executeCommand(
        ServerCommands.RunScalafix,
        textParams,
      )
      _ = assertNoDiff(
        workspace.resolve(".scalafix.conf").readText,
        s"""|rules = [
            |  OrganizeImports
            |]
            |${newSettings.mkString("\n")}
            |""".stripMargin,
      )
      contents = server.bufferContents("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        contents,
        """|import java.util.*
           |import java.util.concurrent.*
           |""".stripMargin,
      )
    } yield ()
  }

}
