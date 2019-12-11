package tests

import scala.meta.internal.metals.QuickFix.ImportMissingSymbol
import scala.meta.internal.metals.MetalsEnrichments._

object CodeActionLspSuite extends BaseLspSuite("codeAction") {

  check(
    "auto-import",
    """|package a
       |
       |object A {
       |  val f = Fut@@ure.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.label("Future", "scala.concurrent")}
        |${ImportMissingSymbol.label("Future", "java.util.concurrent")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin
  )

  def check(
      name: String,
      input: String,
      expectedActions: String,
      expectedCode: String,
      selectedActionIndex: Int = 0
  ): Unit = {
    val path = "a/src/main/scala/a/A.scala"
    testAsync(name) {
      cleanWorkspace()
      for {
        _ <- server.initialize(s"""/metals.json
                                  |{"a":{}}
                                  |/$path
                                  |${input.replaceAllLiterally("@@", "")}
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
        _ = assertNoDiagnostics()
      } yield ()
    }
  }

}
