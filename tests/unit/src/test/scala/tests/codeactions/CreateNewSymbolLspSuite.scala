package tests.codeactions

import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

import munit.Location
import org.eclipse.lsp4j.ShowMessageRequestParams

class CreateNewSymbolLspSuite extends BaseCodeActionLspSuite("createNew") {

  checkNewSymbol(
    "case-class",
    """|package a
       |
       |case class School(name: String, location: <<Location>>)
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Location", "scala.collection.script")}
        |${CreateNewSymbol.title("Location")}""".stripMargin,
    selectedActionIndex = 1,
    pickedKind = "case-class",
    newFile =
      "a/src/main/scala/a/Location.scala" ->
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
        |${CreateNewSymbol.title("Location")}""".stripMargin,
    selectedActionIndex = 1,
    pickedKind = "trait",
    newFile =
      "a/src/main/scala/a/Location.scala" ->
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
        |${CreateNewSymbol.title("Missing")}
        |${CreateNewSymbol.title("Location")}
        |""".stripMargin,
    selectedActionIndex = 1,
    pickedKind = "class",
    newFile =
      "a/src/main/scala/a/Missing.scala" ->
        s"""|package a
            |
            |class Missing {
            |$indent
            |}
            |""".stripMargin,
    expectNoDiagnostics = false
  )

  private def indent = "  "

  def checkNewSymbol(
      name: String,
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
        codeActions <- server.assertCodeAction(path, input, expectedActions)
        _ <- {
          if (selectedActionIndex >= codeActions.length) {
            fail(s"selectedActionIndex ($selectedActionIndex) is out of bounds")
          }
          def isSelectTheKindOfFile(params: ShowMessageRequestParams): Boolean =
            params.getMessage() == NewScalaFile.selectTheKindOfFileMessage
          client.showMessageRequestHandler = { params =>
            if (isSelectTheKindOfFile(params)) {
              params.getActions().asScala.find(_.getTitle() == pickedKind)
            } else {
              None
            }
          }
          client.applyCodeAction(codeActions(selectedActionIndex), server)
        }
        _ <- server.didSave(path)(identity)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
        _ = {
          val (path, content) = newFile
          val absolutePath = workspace.resolve(path)
          assert(absolutePath.exists)
          assertNoDiff(absolutePath.readText, content)
        }
      } yield ()
    }
  }

}
