package tests

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.ListParametrizedCommand
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.RawMetalsInputBoxResult
import scala.meta.internal.metals.newScalaFile.NewFileTypes._
import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.ShowMessageRequestParams

class NewFileLspSuite extends BaseLspSuite("new-file") {

  override def initializationOptions: Option[InitializationOptions] =
    Some(InitializationOptions.Default.copy(inputBoxProvider = Some(true)))

  check("new-worksheet-picked")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Worksheet),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = ""
  )

  check("new-worksheet-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(Worksheet),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = ""
  )

  check("new-worksheet-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(Worksheet),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = ""
  )

  check("new-ammonite-script")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(AmmoniteScript),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = ""
  )

  check("new-ammonite-script-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(AmmoniteScript),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = ""
  )

  check("new-ammonite-script-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(AmmoniteScript),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = ""
  )

  check("new-class")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(Class),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-class-backticked")(
    directory = Some("a/src/main/scala/this/"),
    fileType = Right(Class),
    fileName = Right("type"),
    expectedFilePath = "a/src/main/scala/this/type.scala",
    expectedContent = s"""|package `this`
                          |
                          |class `type` {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-class-name-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(Class),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-class-fully-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Left(Class),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-case-class")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(CaseClass),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin
  )

  check("new-case-class-name-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(CaseClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin
  )

  check("new-case-class-fully-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Left(CaseClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin
  )

  check("new-object-null-dir")(
    directory = None,
    fileType = Right(Object),
    fileName = Right("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-object-null-dir-name-provided")(
    directory = None,
    fileType = Right(Object),
    fileName = Left("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-object-null-dir")(
    directory = None,
    fileType = Left(Object),
    fileName = Left("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-trait-new-dir")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Right("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-trait-new-dir-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Left("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-trait-new-dir-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Right("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-package-object")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(PackageObject),
    fileName = Right(
      ""
    ), // Just given an empty string here because it will never be used for package objects
    expectedFilePath = "a/src/main/scala/foo/package.scala",
    expectedContent = s"""|package object foo {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-package-object-provided")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Left(PackageObject),
    fileName = Right(
      ""
    ), // Just given an empty string here because it will never be used for package objects
    expectedFilePath = "a/src/main/scala/foo/package.scala",
    expectedContent = s"""|package object foo {
                          |$indent
                          |}
                          |""".stripMargin
  )

  check("new-class-on-file")(
    directory = Some("a/src/main/scala/foo/Other.scala"),
    fileType = Right(Class),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
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

  check("new-class-on-file-name-provided")(
    directory = Some("a/src/main/scala/foo/Other.scala"),
    fileType = Right(Class),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
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

  check("new-class-on-file-fully-provided")(
    directory = Some("a/src/main/scala/foo/Other.scala"),
    fileType = Right(Class),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
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
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(Class),
    fileName = Right("Other"),
    expectedFilePath = "a/src/main/scala/foo/Other.scala",
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

  check("scala3-enum")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(Enum),
    fileName = Right("Color"),
    expectedFilePath = "a/src/main/scala/foo/Color.scala",
    expectedContent = s"""|package foo
                          |
                          |enum Color {
                          |${indent}case
                          |}
                          |""".stripMargin,
    scalaVersion = Some(V.scala3)
  )

  check("empty-file-with-package")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(ScalaFile),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |""".stripMargin
  )

  check("new-java-class")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaClass),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-class-name-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-class-fully-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Left(JavaClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-interface")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaInterface),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |interface Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-interface-name-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaInterface),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |interface Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-interface-fully-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Left(JavaInterface),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |interface Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-enum")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaEnum),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |enum Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-enum-name-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaEnum),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |enum Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-enum-fully-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Left(JavaEnum),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = s"""|package foo;
                          |
                          |enum Foo {
                          |$indent
                          |}
                          |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-record")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaRecord),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = """|package foo;
                         |
                         |record Foo() {
                         |
                         |}
                         |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-record-name-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Right(JavaRecord),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = """|package foo;
                         |
                         |record Foo() {
                         |
                         |}
                         |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  check("new-java-record-fully-provided")(
    directory = Some("a/src/main/java/foo/"),
    fileType = Left(JavaRecord),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/java/foo/Foo.java",
    expectedContent = """|package foo;
                         |
                         |record Foo() {
                         |
                         |}
                         |""".stripMargin,
    command = ServerCommands.NewJavaFile
  )

  private lazy val indent = "  "

  type ProvidedFileType = NewFileType
  type PickedFileType = NewFileType
  type Provided = String
  type Picked = String

  /**
   * NewScalaFile request may include @param fileType and @param fileName (2 x Left) in arguments.
   * When one of them missing Metals will use quickpick in order to ask the user about lacking information
   */
  private def check(testName: TestOptions)(
      directory: Option[String],
      fileType: Either[ProvidedFileType, PickedFileType],
      fileName: Either[Provided, Picked],
      expectedFilePath: String,
      expectedContent: String,
      command: ListParametrizedCommand[String] = ServerCommands.NewScalaFile,
      existingFiles: String = "",
      expectedException: List[Class[_]] = Nil,
      scalaVersion: Option[String] = None
  )(implicit loc: Location): Unit =
    test(testName) {
      val localScalaVersion = scalaVersion.getOrElse(V.scala212)
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

      val ft: String =
        fileType match {
          case Left(providedType) => providedType.label
          case Right(pickedType) =>
            client.showMessageRequestHandler = { params =>
              if (isSelectTheKindOfFile(params)) {
                params.getActions().asScala.find(_.getTitle() == pickedType.id)
              } else {
                None
              }
            }
            pickedType.label
        }

      fileName match {
        case Left(_) => ()
        case Right(value) =>
          client.inputBoxHandler = { params =>
            if (isEnterName(params, ft)) {
              RawMetalsInputBoxResult(value = value)
            } else {
              RawMetalsInputBoxResult(cancelled = true)
            }
          }
      }

      val selectFileMessage = fileType match {
        case Left(_) => ""
        case Right(_) => NewScalaFile.selectTheKindOfFileMessage
      }

      val selectNameMessage = fileName match {
        // If given "" as a name, just ignore it (basically for package objects)
        case Left(_) | Right("") => ""
        case Right(_) => "\n" + NewScalaFile.enterNameMessage(ft)
      }

      val expectedMessages =
        selectFileMessage + selectNameMessage

      val args = List(
        directoryUri,
        fileName.fold(identity, _ => null.asInstanceOf[String]),
        fileType.fold(ft => ft.id, _ => null.asInstanceOf[String])
      )

      val futureToRecover = for {
        _ <- initialize(
          s"""/metals.json
             |{
             |  "a": { "scalaVersion" : "$localScalaVersion" }
             |}
             |$existingFiles
          """.stripMargin
        )
        _ <-
          server
            .executeCommand(command, args: _*)
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
