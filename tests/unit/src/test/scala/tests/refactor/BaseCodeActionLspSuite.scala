package tests.refactor

import tests.BaseLspSuite
import scala.meta.internal.metals.MetalsEnrichments._
import munit.Location

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
                                    .replaceAllLiterally("<<", "")
                                    .replaceAllLiterally(">>", "")}
                                  |""".stripMargin)
        _ <- server.didOpen(path)
        codeActions <- server.assertCodeAction(path, input, expectedActions)
        _ <- server.didSave(path) { _ =>
          if (selectedActionIndex >= codeActions.length) {
            fail(s"selectedActionIndex ($selectedActionIndex) is out of bounds")
          }
          client.applyCodeAction(codeActions(selectedActionIndex))
          server.toPath(path).readText
        }
        _ = assertNoDiff(server.bufferContents(path), expectedCode)
        _ = if (expectNoDiagnostics) assertNoDiagnostics() else ()
      } yield ()
    }
  }

}
