package tests.codeactions

import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.codeactions.ImportMissingSymbol
import scala.meta.internal.metals.codeactions.CreateNewFile
import munit.Location
import org.eclipse.lsp4j.ShowMessageRequestParams
import scala.meta.internal.metals.MetalsEnrichments._

class CreateNewFileLspSuite extends BaseCodeActionLspSuite("createNew") {

  checkNewFile(
    "basic",
    """|package a
       |
       |case class School(name: String, location: <<Location>>)
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Location", "scala.collection.script")}
        |${CreateNewFile.title}""".stripMargin,
    selectedActionIndex = 1,
    pickedKind = "case-class",
    newFile =
      "a/src/main/scala/a/Location.scala" ->
        """|package a
           |
           |final case class Location()
           |""".stripMargin
  )

  def checkNewFile(
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
                                    .replaceAllLiterally("<<", "")
                                    .replaceAllLiterally(">>", "")}
                                  |""".stripMargin)
        _ <- server.didOpen(path)
        codeActions <- server.assertCodeAction(path, input, expectedActions)
        _ <- server.didSave(path) { content =>
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
          content
        }
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
