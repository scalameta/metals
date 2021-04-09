package tests.codeactions

import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ExtractRenameMember
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.ShowMessageRequestParams

class CreateNewSymbolLspSuite extends BaseCodeActionLspSuite("createNew") {

  val docToolName = "javax.tools.DocumentationTool"

  checkNewSymbol(
    "case-class",
    """|package a
       |
       |case class School(name: String, location: <<Location>>)
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Location", "scala.collection.script")}
        |${ImportMissingSymbol.title("Location", "javax.xml.stream")}
        |${ImportMissingSymbol.title("Location", "javax.tools.JavaFileManager")}
        |${ImportMissingSymbol.title("Location", docToolName)}
        |${CreateNewSymbol.title("Location")}""".stripMargin,
    selectedActionIndex = 4,
    pickedKind = "case-class",
    newFile = "a/src/main/scala/a/Location.scala" ->
      """|package a
         |
         |final case class Location()
         |""".stripMargin
  )

  checkNewSymbol(
    "trait",
    """|package a
       |
       |case class School(name: String, location: <<Location>>)
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Location", "scala.collection.script")}
        |${ImportMissingSymbol.title("Location", "javax.xml.stream")}
        |${ImportMissingSymbol.title("Location", "javax.tools.JavaFileManager")}
        |${ImportMissingSymbol.title("Location", docToolName)}
        |${CreateNewSymbol.title("Location")}""".stripMargin,
    selectedActionIndex = 4,
    pickedKind = "trait",
    newFile = "a/src/main/scala/a/Location.scala" ->
      s"""|package a
          |
          |trait Location {
          |$indent
          |}
          |""".stripMargin
  )

  checkNewSymbol(
    "multi",
    """|package a
       |
       |<<case class School(name: Missing, location: Location)>>
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Location", "scala.collection.script")}
        |${ImportMissingSymbol.title("Location", "javax.xml.stream")}
        |${ImportMissingSymbol.title("Location", "javax.tools.JavaFileManager")}
        |${ImportMissingSymbol.title("Location", docToolName)}
        |${CreateNewSymbol.title("Missing")}
        |${CreateNewSymbol.title("Location")}
        |${ExtractRenameMember.renameFileAsClassTitle(
      fileName = "A.scala",
      memberName = "School"
    )}
        |""".stripMargin,
    selectedActionIndex = 4,
    pickedKind = "class",
    newFile = "a/src/main/scala/a/Missing.scala" ->
      s"""|package a
          |
          |class Missing {
          |$indent
          |}
          |""".stripMargin,
    expectNoDiagnostics = false
  )

  def checkNewSymbol(
      name: TestOptions,
      input: String,
      expectedActions: String,
      pickedKind: String,
      newFile: (String, String),
      selectedActionIndex: Int = 0,
      expectNoDiagnostics: Boolean = true
  )(implicit loc: Location): Unit = {
    val path = "a/src/main/scala/a/A.scala"
    test(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(s"""/metals.json
                                  |{"a":{}}
                                  |/$path
                                  |${input
          .replace("<<", "")
          .replace(">>", "")}
                                  |""".stripMargin)
        _ <- server.didOpen(path)
        codeActions <-
          server.assertCodeAction(path, input, expectedActions, Nil)
        _ <- {
          def isSelectTheKindOfFile(params: ShowMessageRequestParams): Boolean =
            params.getMessage() == NewScalaFile.selectTheKindOfFileMessage
          client.showMessageRequestHandler = { params =>
            if (isSelectTheKindOfFile(params)) {
              params.getActions().asScala.find(_.getTitle() == pickedKind)
            } else {
              None
            }
          }
          client.applyCodeAction(selectedActionIndex, codeActions, server)
        }
        _ <- server.didSave(path)(identity)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
        _ = {
          val (path, content) = newFile
          val absolutePath = workspace.resolve(path)
          assert(
            absolutePath.exists,
            s"File $absolutePath should have been created"
          )
          assertNoDiff(absolutePath.readText, content)
        }
      } yield ()
    }
  }

  private def indent = "  "

}
