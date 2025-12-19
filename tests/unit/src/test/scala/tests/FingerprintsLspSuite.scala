package tests

import scala.meta.internal.metals.ServerCommands

class FingerprintsLspSuite extends BaseLspSuite("fingerprints") {
  // Ignored: test hangs indefinitely
  test("break".ignore, maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/a/Names.scala
          |package a
          |class Names {
          |  val name = "John"
          |  val surname = "Nowak"
          |  override def toString() = name + " " + surname
          |}
          |/a/src/main/scala/a/Adresses.scala
          |package a
          |class Adresses {
          |  val street = "Forbes"
          |  val number = 123
          |  override def toString() = street + " " + number
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Names.scala")
      _ <- server.didOpen("a/src/main/scala/a/Adresses.scala")
      _ <- server.executeCommand(ServerCommands.CascadeCompile)
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = server.assertReferenceDefinitionBijection()
      _ <- server.didChange("a/src/main/scala/a/Names.scala")(text =>
        text.replace("+ surname", "+ surname2")
      )
      _ <- server.didSave("a/src/main/scala/a/Names.scala")
      _ <- server.didChange("a/src/main/scala/a/Adresses.scala")(text =>
        text.replace("+ number", "+ number2")
      )
      _ <- server.didSave("a/src/main/scala/a/Adresses.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Adresses.scala:5:44: error: not found: value number2
           |  override def toString() = street + " " + number2
           |                                           ^^^^^^^
           |a/src/main/scala/a/Names.scala:5:42: error: not found: value surname2
           |  override def toString() = name + " " + surname2
           |                                         ^^^^^^^^
           |""".stripMargin,
      )
      _ <- server.didClose("a/src/main/scala/a/Adresses.scala")
      _ <- server.shutdown()
      newServer = server.copy()
      _ <- newServer.initialize()
      _ <- newServer.initialized()
      _ <- newServer.didOpen("a/src/main/scala/a/Names.scala")
      _ <- newServer.didOpen("a/src/main/scala/a/Adresses.scala")
      _ <- newServer.executeCommand(ServerCommands.CascadeCompile)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/Adresses.scala:5:44: error: not found: value number2
           |  override def toString() = street + " " + number2
           |                                           ^^^^^^^
           |a/src/main/scala/a/Names.scala:5:42: error: not found: value surname2
           |  override def toString() = name + " " + surname2
           |                                         ^^^^^^^^
           |""".stripMargin,
      )
      workspaceRefs <- server.workspaceReferences()
      _ = assertNoDiff(
        workspaceRefs.referencesFormat,
        """|=============
           |= a/Adresses#
           |=============
           |a/src/main/scala/a/Adresses.scala:2:7: a/Adresses#
           |class Adresses {
           |      ^^^^^^^^
           |====================
           |= a/Adresses#number.
           |====================
           |a/src/main/scala/a/Adresses.scala:4:7: a/Adresses#number.
           |  val number = 123
           |      ^^^^^^
           |====================
           |= a/Adresses#street.
           |====================
           |a/src/main/scala/a/Adresses.scala:3:7: a/Adresses#street.
           |  val street = "Forbes"
           |      ^^^^^^
           |a/src/main/scala/a/Adresses.scala:5:29: a/Adresses#street.
           |  override def toString() = street + " " + number2
           |                            ^^^^^^
           |========================
           |= a/Adresses#toString().
           |========================
           |a/src/main/scala/a/Adresses.scala:5:16: a/Adresses#toString().
           |  override def toString() = street + " " + number2
           |               ^^^^^^^^
           |==========
           |= a/Names#
           |==========
           |a/src/main/scala/a/Names.scala:2:7: a/Names#
           |class Names {
           |      ^^^^^
           |===============
           |= a/Names#name.
           |===============
           |a/src/main/scala/a/Names.scala:3:7: a/Names#name.
           |  val name = "John"
           |      ^^^^
           |a/src/main/scala/a/Names.scala:5:29: a/Names#name.
           |  override def toString() = name + " " + surname2
           |                            ^^^^
           |==================
           |= a/Names#surname.
           |==================
           |a/src/main/scala/a/Names.scala:4:7: a/Names#surname.
           |  val surname = "Nowak"
           |      ^^^^^^^
           |=====================
           |= a/Names#toString().
           |=====================
           |a/src/main/scala/a/Names.scala:5:16: a/Names#toString().
           |  override def toString() = name + " " + surname2
           |               ^^^^^^^^
           |""".stripMargin,
      )
      _ <- newServer.shutdown()
    } yield ()

  }
}
