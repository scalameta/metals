package tests

import java.nio.file.Files
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsNewScalaFileParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete

class NewFilesLspSuite extends BaseLspSuite("new-files") {
  test("new-worksheet") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
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
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/foo").toNIO
    )
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/foo/").toURI.toString,
          "Foo",
          "class"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/foo/Foo.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/foo/Foo.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Foo.scala").readText,
        """|package foo
           |
           |class Foo {
           |  
           |}
           |""".stripMargin
      )

    } yield ()
  }

  test("new-object") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/").toURI.toString,
          "Bar",
          "object"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/Bar.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/Bar.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/Bar.scala").readText,
        """|object Bar {
           |  
           |}
           |""".stripMargin
      )

    } yield ()
  }

  test("new-trait") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/").toURI.toString,
          "Baz",
          "trait"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/Baz.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/Baz.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/Baz.scala").readText,
        """|trait Baz {
           |  
           |}
           |""".stripMargin
      )

    } yield ()
  }

  test("new-package-object") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/foo").toNIO
    )
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        MetalsNewScalaFileParams(
          workspace.resolve("a/src/main/scala/foo").toURI.toString,
          "",
          "package-object"
        )
      )
      _ = assert(
        workspace.resolve("a/src/main/scala/foo/package.scala").exists
      )
      _ <- server.didSave("a/src/main/scala/foo/package.scala")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/package.scala").readText,
        """|package object foo {
           |  
           |}
           |""".stripMargin
      )

    } yield ()
  }

}
