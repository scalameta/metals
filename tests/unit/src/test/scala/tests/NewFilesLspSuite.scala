package tests

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsInputBoxResult
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.ServerCommands

import munit.TestOptions
import org.eclipse.lsp4j.ShowMessageRequestParams

class NewFilesLspSuite extends BaseLspSuite("new-files") {

  override def initializationOptions: Option[InitializationOptions] =
    Some(InitializationOptions.Default.copy(inputBoxProvider = true))

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
    s"""|package foo
        |
        |class Foo {
        |$indent
        |}
        |""".stripMargin
  )

  check("new-case-class")(
    Some("a/src/main/scala/foo/"),
    "case-class",
    Some("Foo"),
    "a/src/main/scala/foo/Foo.scala",
    """|package foo
       |
       |final case class Foo()
       |""".stripMargin
  )

  check("new-object-null-dir")(
    directory = None,
    "object",
    Some("Bar"),
    "Bar.scala",
    s"""|object Bar {
        |$indent
        |}
        |""".stripMargin
  )

  check("new-trait-new-dir")(
    Some("a/src/main/scala/"),
    "trait",
    Some("bar/Baz"),
    "a/src/main/scala/bar/Baz.scala",
    s"""|package bar
        |
        |trait Baz {
        |$indent
        |}
        |""".stripMargin
  )

  check("new-package-object")(
    Some("a/src/main/scala/foo"),
    "package-object",
    name = None,
    "a/src/main/scala/foo/package.scala",
    s"""|package object foo {
        |$indent
        |}
        |""".stripMargin
  )

  check("new-class-on-file")(
    Some("a/src/main/scala/foo/Other.scala"),
    "class",
    Some("Foo"),
    "a/src/main/scala/foo/Foo.scala",
    s"""|package foo
        |
        |class Foo {
        |$indent
        |}
        |""".stripMargin,
    existingFiles = """|/a/src/main/scala/foo/Other.scala
                       |package foo
                       |
                       |class Other
                       |""".stripMargin
  )

  check("existing-file")(
    Some("a/src/main/scala/foo"),
    "class",
    Some("Other"),
    "a/src/main/scala/foo/Other.scala",
    expectedContent = s"""|package foo
                          |
                          |class Other {
                          | val withContent = true
                          |}
                          |""".stripMargin,
    existingFiles = s"""|/a/src/main/scala/foo/Other.scala
                        |package foo
                        |
                        |class Other {
                        | val withContent = true
                        |}
                        |""".stripMargin,
    expectedException = List(classOf[FileAlreadyExistsException])
  )

  private def indent = "  "

  private def check(testName: TestOptions)(
      directory: Option[String],
      pickedKind: String,
      name: Option[String],
      expectedFilePath: String,
      expectedContent: String,
      existingFiles: String = "",
      expectedException: List[Class[_]] = Nil
  ): Unit =
    test(testName) {
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

      val futureToRecover = for {
        _ <- server.initialize(s"""
                                  |/metals.json
                                  |{
                                  |  "a": { }
                                  |}
                                  |$existingFiles
                                  |""".stripMargin)
        _ <-
          server
            .executeCommand(
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

      futureToRecover
        .recover {
          case e if expectedException.contains(e.getClass()) =>
            assertNoDiff(
              expectedFilePathAbsolute.readText,
              expectedContent
            )
          case other =>
            throw other
        }
    }

  private def isSelectTheKindOfFile(params: ShowMessageRequestParams): Boolean =
    params.getMessage() == NewScalaFile.selectTheKindOfFileMessage

  private def isEnterName(params: MetalsInputBoxParams, kind: String): Boolean =
    params.prompt == NewScalaFile.enterNameMessage(kind)

}
