package tests.codeactions

import scala.meta.internal.metals.MetalsEnrichments._

import munit.Location
import munit.TestOptions
import tests.BaseLspSuite

abstract class BaseCodeActionLspSuite(suiteName: String)
    extends BaseLspSuite(suiteName) {

  def check(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0,
      expectNoDiagnostics: Boolean = true,
      kind: List[String] = Nil,
      scalafixConf: String = "",
      scalacOptions: List[String] = Nil
  )(implicit loc: Location): Unit = {
    val scalacOptionsJson =
      s""""scalacOptions": ["${scalacOptions.mkString("\",\"")}"]"""
    val path = "a/src/main/scala/a/A.scala"
    test(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(s"""/metals.json
                                  |{"a":{$scalacOptionsJson}}
                                  |$scalafixConf
                                  |/$path
                                  |${input
          .replace("<<", "")
          .replace(">>", "")}
                                  |""".stripMargin)
        _ <- server.didOpen(path)
        codeActions <-
          server.assertCodeAction(path, input, expectedActions, kind)
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
