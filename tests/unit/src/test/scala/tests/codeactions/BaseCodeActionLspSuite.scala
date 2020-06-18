package tests.codeactions

import scala.meta.internal.metals.MetalsEnrichments._

import munit.Location
import tests.BaseLspSuite

abstract class BaseCodeActionLspSuite(suiteName: String)
    extends BaseLspSuite(suiteName) {

  def check(
      name: String,
      input: String,
      expectedActions: String,
      expectedCode: String,
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
        _ <- server.didSave(path) { _ =>
          if (codeActions.nonEmpty) {
            if (selectedActionIndex >= codeActions.length) {
              fail(
                s"selectedActionIndex ($selectedActionIndex) is out of bounds"
              )
            }
            client.applyCodeAction(codeActions(selectedActionIndex), server)
          }
          server.toPath(path).readText
        }
        _ = assertNoDiff(server.bufferContents(path), expectedCode)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
      } yield ()
    }
  }

}
