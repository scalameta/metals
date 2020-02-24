package tests

import java.nio.file.Files
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.ClientExperimentalCapabilities
import scala.meta.internal.metals.MetalsInputBoxParams
import org.eclipse.lsp4j.ShowMessageRequestParams

class NewFilesLspSuite extends BaseLspSuite("new-files") {
  override def experimentalCapabilities
      : Option[ClientExperimentalCapabilities] =
    Some(ClientExperimentalCapabilities(inputBoxProvider = true))

  check("new-worksheet")(
    Some("a/src/main/scala/"),
    "worksheet",
    Some("Foo"),
    "a/src/main/scala/Foo.worksheet.sc",
    ""
  )

  check("new-class")(
    Some("a/src/main/scala/foo/"),
    "class",
    Some("Foo"),
    "a/src/main/scala/foo/Foo.scala",
    """|package foo
       |
       |class Foo {
       |  
       |}
       |""".stripMargin
  )

  check("new-object-null-dir")(
    directory = None,
    "object",
    Some("Bar"),
    "Bar.scala",
    """|object Bar {
       |  
       |}
       |""".stripMargin
  )

  check("new-trait-new-dir")(
    Some("a/src/main/scala/"),
    "trait",
    Some("bar/Baz"),
    "a/src/main/scala/bar/Baz.scala",
    """|package bar
       |
       |trait Baz {
       |  
       |}
       |""".stripMargin
  )

  check("new-package-object")(
    Some("a/src/main/scala/foo"),
    "package-object",
    name = None,
    "a/src/main/scala/foo/package.scala",
    """|package object foo {
       |  
       |}
       |""".stripMargin
  )

  private def check(testName: String)(
      directory: Option[String],
      pickedKind: String,
      name: Option[String],
      expectedFilePath: String,
      expectedContent: String
  ): Unit = test(testName) {
    val directoryUri = directory.fold(null.asInstanceOf[String])(
      workspace.resolve(_).toURI.toString()
    )
    val expectedFilePathAbsolute = workspace.resolve(expectedFilePath)

    RecursivelyDelete(expectedFilePathAbsolute)
    cleanCompileCache("a")
    RecursivelyDelete(workspace.resolve("a"))
    Files.createDirectories(
      workspace.resolve("a/src/main/scala/").toNIO
    )

    client.showMessageRequestHandler = { params =>
      if (isSelectTheKindOfFile(params)) {
        params.getActions().asScala.find(_.getTitle() == pickedKind)
      } else {
        None
      }
    }
    name.foreach { name =>
      client.inputBoxHandler = { params =>
        if (isEnterName(params, pickedKind)) {
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
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaFile.id,
        directoryUri
      )
      _ = {
        assertNoDiff(
          client.workspaceMessageRequests,
          expectedMessages
        )
        assert(expectedFilePathAbsolute.exists)
        assertNoDiff(
          expectedFilePathAbsolute.readText,
          expectedContent
        )
      }
    } yield ()
  }

  private def isSelectTheKindOfFile(params: ShowMessageRequestParams): Boolean =
    params.getMessage() == NewScalaFile.selectTheKindOfFileMessage

  private def isEnterName(params: MetalsInputBoxParams, kind: String): Boolean =
    params.prompt == NewScalaFile.enterNameMessage(kind)

}
