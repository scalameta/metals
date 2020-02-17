package tests

import java.nio.file.Files
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsInputBoxResult

class NewFilesLspSuite extends BaseLspSuite("new-files") {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(isInputBoxEnabled = true)

  test("new-worksheet") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == "worksheet")
      } else {
        None
      }
    }
    client.inputBoxHandler = { params =>
      if (NewScalaFile.isEnterName(params, "worksheet")) {
        Some(new MetalsInputBoxResult(value = "Foo"))
      } else {
        None
      }
    }
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        workspace.resolve("a/src/main/scala/").toURI.toString
      )
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          List(
            NewScalaFile.selectTheKindOfFileMessage,
            NewScalaFile.enterNameMessage("worksheet")
          ).mkString("\n")
        )
        assert(workspace.resolve("a/src/main/scala/Foo.worksheet.sc").exists)
      }
    } yield ()
  }

  test("new-class") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/foo").toNIO
    )
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == "class")
      } else {
        None
      }
    }
    client.inputBoxHandler = { params =>
      if (NewScalaFile.isEnterName(params, "class")) {
        Some(new MetalsInputBoxResult(value = "Foo"))
      } else {
        None
      }
    }
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        workspace.resolve("a/src/main/scala/foo/").toURI.toString
      )
      _ = {
        assert(
          workspace.resolve("a/src/main/scala/foo/Foo.scala").exists
        )
        assertNoDiff(
          client.workspaceMessageRequests,
          List(
            NewScalaFile.selectTheKindOfFileMessage,
            NewScalaFile.enterNameMessage("class")
          ).mkString("\n")
        )
        assertNoDiff(
          workspace.resolve("a/src/main/scala/foo/Foo.scala").readText,
          """|package foo
             |
             |class Foo {
             |  
             |}
             |""".stripMargin
        )
      }

    } yield ()
  }

  test("new-object-null-dir") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    RecursivelyDelete(workspace.resolve("Bar.scala"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == "object")
      } else {
        None
      }
    }
    client.inputBoxHandler = { params =>
      if (NewScalaFile.isEnterName(params, "object")) {
        Some(new MetalsInputBoxResult(value = "Bar"))
      } else {
        None
      }
    }
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        null.asInstanceOf[String]
      )
      _ = {
        assert(
          workspace.resolve("Bar.scala").exists
        )
        assertNoDiff(
          client.workspaceMessageRequests,
          List(
            NewScalaFile.selectTheKindOfFileMessage,
            NewScalaFile.enterNameMessage("object")
          ).mkString("\n")
        )
        assertNoDiff(
          workspace.resolve("Bar.scala").readText,
          """|object Bar {
             |  
             |}
             |""".stripMargin
        )
      }

    } yield ()
  }

  test("new-trait") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == "trait")
      } else {
        None
      }
    }
    client.inputBoxHandler = { params =>
      if (NewScalaFile.isEnterName(params, "trait")) {
        Some(new MetalsInputBoxResult(value = "Baz"))
      } else {
        None
      }
    }
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        workspace.resolve("a/src/main/scala/").toURI.toString
      )
      _ = {
        assert(
          workspace.resolve("a/src/main/scala/Baz.scala").exists
        )
        assertNoDiff(
          client.workspaceMessageRequests,
          List(
            NewScalaFile.selectTheKindOfFileMessage,
            NewScalaFile.enterNameMessage("trait")
          ).mkString("\n")
        )
        assertNoDiff(
          workspace.resolve("a/src/main/scala/Baz.scala").readText,
          """|trait Baz {
             |  
             |}
             |""".stripMargin
        )
      }

    } yield ()
  }

  test("new-package-object") {
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/foo").toNIO
    )
    client.showMessageRequestHandler = { params =>
      if (NewScalaFile.isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == "package-object")
      } else {
        None
      }
    }
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        workspace.resolve("a/src/main/scala/foo").toURI.toString
      )
      _ = {
        assert(
          workspace.resolve("a/src/main/scala/foo/package.scala").exists
        )
        assertNoDiff(
          client.workspaceMessageRequests,
          NewScalaFile.selectTheKindOfFileMessage
        )

        assertNoDiff(
          workspace.resolve("a/src/main/scala/foo/package.scala").readText,
          """|package object foo {
             |  
             |}
             |""".stripMargin
        )
      }

    } yield ()
  }

}
