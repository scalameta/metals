package tests

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsNewScalaFileParams
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
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/").toURI.toString,
          "Foo",
          "worksheet"
        )
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
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/myPackage/").toURI.toString,
          "Foo",
          "class"
        )
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

  test("new-object") {
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
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/myPackage/").toURI.toString,
          "Bar",
          "object"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/myPackage/Bar.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/myPackage/Bar.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/myPackage/Bar.scala").readText,
        """|package myPackage
           |
           |object Bar {
           |
           |}
           |""".stripMargin
      )

    } yield ()
  }

  test("new-trait") {
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
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/myPackage/").toURI.toString,
          "Baz",
          "trait"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/myPackage/Baz.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/myPackage/Baz.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/myPackage/Baz.scala").readText,
        """|package myPackage
           |
           |trait Baz {
           |
           |}
           |""".stripMargin
      )

    } yield ()
  }

}
