package tests

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsEnrichments._

class NewFilesLspSuite extends BaseLspSuite("new-files") {
  test("new-worksheet") {
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |/a/src/main/scala/dummy
                                |
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaWorksheet.id,
        workspace.resolve("a/src/main/scala/").toURI.toString,
        "Foo"
      )
      _ = assert(workspace.resolve("a/src/main/scala/Foo.worksheet.sc").exists)
    } yield ()
  }

  test("new-class") {
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |/a/src/main/scala/myPackage/dummy
                                |
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaClass.id,
        workspace.resolve("a/src/main/scala/myPackage/").toURI.toString,
        "Foo",
        "class"
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/myPackage/Foo.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/myPackage/Foo.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/myPackage/Foo.scala").readText,
        """|package myPackage
           |
           |class Foo {
           |
           |}
           |""".stripMargin
      )

    } yield ()
  }
}
