package tests.scalafix

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.RunScalafixRulesParams
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.RawMetalsQuickPickResult

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams
import tests.BaseLspSuite

class ScalafixRunOnlyLspSuite extends BaseLspSuite("run-scalafix-rules") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        quickPickProvider = Some(true)
      )
    )

  private def params(file: String, rules: List[String]) =
    RunScalafixRulesParams(
      new TextDocumentPositionParams(
        new TextDocumentIdentifier(
          workspace.resolve(file).toURI.toString()
        ),
        new Position(0, 0),
      ),
      if (rules.isEmpty) null else rules.asJava,
    )

  private val mainFile = "a/src/main/scala/Main.scala"

  private val scalafixConf: String =
    """|/.scalafix.conf
       |rules = [
       |  ExplicitResultTypes,
       |  RemoveUnused
       |]
       |
       |ExplicitResultTypes.rewriteStructuralTypesToNamedSubclass = false
       |
       |RemoveUnused.imports = false
       |""".stripMargin

  private val workspaceOutlay: String =
    s"""/metals.json
       |{"a":{"scalacOptions": ["-Wunused"] }}
       |$scalafixConf
       |/$mainFile
       |// unused import
       |import java.io.File
       |class A
       |object Main{
       |  def debug { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
       |  val addTypeHere = new A{}
       |  private val notUsed = 123
       |}
       |""".stripMargin

  test("single rule") {
    cleanWorkspace()
    for {
      _ <- initialize(workspaceOutlay)
      _ <- server.didOpen(mainFile)
      _ <- server.executeCommand(
        ServerCommands.ScalafixRunOnly,
        params(mainFile, List("ExplicitResultTypes")),
      )
      contents = server.bufferContents(mainFile)
      _ = assertNoDiff(
        contents,
        """|// unused import
           |import java.io.File
           |class A
           |object Main{
           |  def debug { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
           |  val addTypeHere: A = new A{}
           |  private val notUsed = 123
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("no rule prompts user") {
    cleanWorkspace()
    server.client.quickPickHandler =
      _ => RawMetalsQuickPickResult("RemoveUnused", cancelled = false)

    for {
      _ <- initialize(workspaceOutlay)
      _ <- server.didOpen(mainFile)
      _ <- server.executeCommand(
        ServerCommands.ScalafixRunOnly,
        params(mainFile, List.empty[String]),
      )
      contents = server.bufferContents(mainFile)
      _ = assertNoDiff(
        contents,
        """|// unused import
           |import java.io.File
           |class A
           |object Main{
           |  def debug { println("debug") } // ProcedureSyntax rule is not defined, should not be changed
           |  val addTypeHere = new A{}
           |
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}
