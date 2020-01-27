package tests

class WindowStateDidChangeLspSuite
    extends BaseLspSuite("window-state-did-change") {
  test("basic") {
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

      _ = server.windowStateDidChange(focused = false)
      _ = assert(server.server.pauseables.isPaused.get())
      didChange = server.didChange("a/src/main/scala/a/A.scala")(
        _.replaceAllLiterally("= 42", "=")
      )
      _ = Thread.sleep(10) // give the parser a moment to complete.
      // Assert didChange is still running because parsing is paused.
      _ = assert(!didChange.isCompleted)

      _ = server.windowStateDidChange(focused = true)
      _ = assert(!server.server.pauseables.isPaused.get())
      _ <- didChange
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:4:1: error: illegal start of simple expression
           |}
           |^
           |""".stripMargin
      )
    } yield ()
  }
}
