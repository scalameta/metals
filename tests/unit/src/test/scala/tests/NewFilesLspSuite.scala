package tests

import java.nio.file.Files
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.ClientExperimentalCapabilities
import scala.concurrent.Future
import scala.meta.io.AbsolutePath

class NewFilesLspSuite extends BaseLspSuite("new-files") {
  override def experimentalCapabilities
      : Option[ClientExperimentalCapabilities] =
    Some(ClientExperimentalCapabilities(inputBoxProvider = true))

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
      _ <- check(
        workspace.resolve("a/src/main/scala/").toURI.toString(),
        "worksheet",
        Some("Foo"),
        workspace.resolve("a/src/main/scala/Foo.worksheet.sc"),
        ""
      )
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
      _ <- check(
        workspace.resolve("a/src/main/scala/foo/").toURI.toString(),
        "class",
        Some("Foo"),
        workspace.resolve("a/src/main/scala/foo/Foo.scala"),
        """|package foo
           |
           |class Foo {
           |  
           |}
           |""".stripMargin
      )
    } yield ()
  }

  test("new-object-null-dir") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    RecursivelyDelete(workspace.resolve("Bar.scala"))
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
      _ <- check(
        directoryUri = null.asInstanceOf[String],
        "object",
        Some("Bar"),
        workspace.resolve("Bar.scala"),
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
      _ <- check(
        workspace.resolve("a/src/main/scala/").toURI.toString(),
        "trait",
        Some("Baz"),
        workspace.resolve("a/src/main/scala/Baz.scala"),
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
      _ <- check(
        workspace.resolve("a/src/main/scala/foo").toURI.toString(),
        "package-object",
        name = None,
        workspace.resolve("a/src/main/scala/foo/package.scala"),
        """|package object foo {
           |  
           |}
           |""".stripMargin
      )
    } yield ()
  }

  private def check(
      directoryUri: String,
      pickedKind: String,
      name: Option[String],
      expectedFilePath: AbsolutePath,
      expectedContent: String
  ): Future[Unit] = {
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == pickedKind)
      } else {
        None
      }
    }
    name.foreach { name =>
      client.inputBoxHandler = { params =>
        if (NewScalaFile.isEnterName(params, pickedKind)) {
          Some(new MetalsInputBoxResult(value = name))
        } else {
          None
        }
      }
    }

    val expectedMessages =
      NewScalaFile.selectTheKindOfFileMessage + name.fold("")(_ =>
        "\n" + NewScalaFile.enterNameMessage(pickedKind)
      )

    for {
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        directoryUri
      )
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          expectedMessages
        )
        assert(expectedFilePath.exists)
        assertNoDiff(
          expectedFilePath.readText,
          expectedContent
        )
      }
    } yield ()
  }

}
