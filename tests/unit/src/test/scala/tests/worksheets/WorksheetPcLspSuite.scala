package tests.worksheets

import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class WorksheetPcLspSuite extends BaseLspSuite(s"worksheet-pc") {

  override def userConfig: UserConfiguration = super.userConfig.copy(
    presentationCompilerDiagnostics = true,
    fallbackScalaVersion = Some(V.latestScala3Next),
  )

  test("diagnostics") {
    cleanWorkspace()
    val path = "a/src/main/scala/hi.worksheet.sc"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${V.latestScala3Next}"
           |  }
           |}
           |/${path}
           |211 + 122 // no errors
           |211 + 122 // no errors
           |object O:
           |  def file: String = "a"
           |
           |O.file
           |O.file
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.didChange(path)(_.replace("122", "123"))
      _ = assertNoDiff(
        // separate issue, should be silcenced separately
        client.workspaceDiagnostics,
        """|a/src/main/scala/hi.worksheet.sc:1:1: warning: A pure expression does nothing in statement position
           |211 + 123 // no errors
           |^^^^^^^^^
           |a/src/main/scala/hi.worksheet.sc:2:1: warning: A pure expression does nothing in statement position
           |211 + 123 // no errors
           |^^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.assertReferencesSubquery(
        path,
        "O.fil@@e",
        """|a/src/main/scala/hi.worksheet.sc:4:7: reference
           |  def file: String = "a"
           |      ^^^^
           |a/src/main/scala/hi.worksheet.sc:6:3: reference
           |O.file
           |  ^^^^
           |a/src/main/scala/hi.worksheet.sc:7:3: reference
           |O.file
           |  ^^^^""".stripMargin,
      )
    } yield ()
  }

}
