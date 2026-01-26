package tests.worksheets

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.metals.InitializationOptions
import tests.TestingServer

class Issue7090LspSuite extends tests.BaseWorksheetLspSuite(V.scala213) {

  override protected def initializationOptions
      : Option[InitializationOptions] = {
    Some(
      TestingServer.TestDefault.copy(
        didFocusProvider = Some(false)
      )
    )
  }

  test("recompile-worksheet-without-did-focus") {
    cleanWorkspace()
    val worksheet = "a/src/main/scala/foo/Main.worksheet.sc"
    val otherFile = "a/src/main/scala/foo/Other.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/$worksheet
           |val x = 1
           |/$otherFile
           |object Other { val y = 2 }
           |""".stripMargin
      )
      _ <- server.didOpen(worksheet)
      _ <- server.assertInlayHints(
        worksheet,
        """|val x = 1/* // : Int = 1*/
           |""".stripMargin,
      )
      _ <- server.didOpen(otherFile)
      _ = {
        val path = server.toPath(worksheet)
        server.buffers.put(path, """val x: Int = "string" """)
      }
      _ <- server.didSave(worksheet)
      _ = assert(
        server.client.workspaceDiagnostics.contains("type mismatch"),
        "Worksheet should be evaluated on save",
      )
    } yield ()
  }
}
