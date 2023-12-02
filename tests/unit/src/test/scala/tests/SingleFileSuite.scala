package tests

class SingleFileSuite extends BaseCompletionLspSuite("workspaceFolderSuite") {

  test("basic") {
    cleanWorkspace()
    writeLayout(
      """|/A.scala
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
        """|A.scala:2:16: error: Found:    ("aaa" : String)
           |Required: Int
           |  val i: Int = "aaa"
           |               ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

}
