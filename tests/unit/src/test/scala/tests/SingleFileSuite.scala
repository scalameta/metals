package tests

class SingleFileSuite extends BaseCompletionLspSuite("workspaceFolderSuite") {

  // DATABRICKS: the test hangs indefinitely
  test("basic".ignore) {
    cleanWorkspace()
    val newFileContent =
      s"""|//> using scala ${BuildInfo.scalaVersion}
          |case class MyObjectA() {
          |  val <<foo@@>>: String = "aaa"
          |  val j = <<foo>> + "a"
          |}
          |""".stripMargin

    writeLayout(
      s"""|/A.scala
          |//> using scala ${BuildInfo.scalaVersion}
          |case class MyObjectA() {
          |  val i: Int = "aaa"
          |}
          |""".stripMargin
    )
    for {
      _ <- initialize(Map.empty[String, String], expectError = false)
      _ <- server.didOpen("A.scala")
      _ = assertNoDiff(
        server.client.workspaceDiagnostics,
        """|A.scala:3:16: error: type mismatch;
           | found   : String("aaa")
           | required: Int
           |  val i: Int = "aaa"
           |               ^^^^^
           |""".stripMargin,
      )
      _ <- server.didChange("A.scala")(_ =>
        newFileContent.replaceAll("<<|>>|@@", "")
      )
      _ <- server.assertReferences(
        "A.scala",
        newFileContent.replaceAll("<<|>>", ""),
        Map("A.scala" -> newFileContent.replaceAll("@@", "")),
        Map("A.scala" -> newFileContent.replaceAll("<<|>>|@@", "")),
      )
    } yield ()
  }

}
