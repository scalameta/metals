package tests

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MtagsResolver

class RemovedScalaLspSuite extends BaseLspSuite("cascade") {

  override protected def mtagsResolver: MtagsResolver = MtagsResolver.default()

  test("check-support") {
    cleanWorkspace()
    val withDocumentHighlight =
      """|package a
         |object A {
         |  val <<ag@@e>> = 42
         |  <<age>> + 12
         |}""".stripMargin
    val fileContents =
      withDocumentHighlight
        .replace(">>", "")
        .replace("<<", "")
        .replace("@@", "")
    val expected = withDocumentHighlight.replace("@@", "")
    val edit = withDocumentHighlight.replace(">>", "").replace("<<", "")
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { "scalaVersion" : "2.13.1" }
           |}
           |/a/src/main/scala/a/A.scala
           |$fileContents
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        Messages.DeprecatedRemovedScalaVersion.message(Set("2.13.1")),
      )
      // document highlight is available in 0.11.10 for 3.0.0
      _ <- server.assertHighlight(
        "a/src/main/scala/a/A.scala",
        edit,
        expected,
      )
      // semantic highlight was not available in 0.11.10
      _ <- server.didChangeConfiguration(
        """{
          |  "enable-semantic-highlighting": true
          |}
          |""".stripMargin
      )
      _ <- server.assertSemanticHighlight(
        "a/src/main/scala/a/A.scala",
        "",
        fileContents,
      )
    } yield ()
  }
}
