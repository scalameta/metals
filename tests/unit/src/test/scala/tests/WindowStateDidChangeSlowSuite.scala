package tests

object WindowStateDidChangeSlowSuite
    extends BaseSlowSuite("window-state-did-change") {
  testAsync("compile-after-focus") {
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{ "a": {} }
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val x = 42
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ = server.windowStateDidChange(false)
      didSave = server.didSave("a/src/main/scala/a/A.scala")(
        _.replaceAllLiterally("val x", "x")
      )
      _ = assertNoDiagnostics()
      _ = server.windowStateDidChange(true)
      _ <- didSave
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:3:3: error: not found: value x
           |  x = 42
           |  ^
        """.stripMargin
      )
    } yield ()
  }
}
