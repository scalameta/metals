package tests

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

import scala.util.Properties

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

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(InitializationOptions.Default.copy(inputBoxProvider = Some(true)))

  checkScala("new-worksheet-picked")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Worksheet),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = "",
  )

  checkScala("new-worksheet-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(Worksheet),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = "",
  )

  checkScala("new-worksheet-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(Worksheet),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.worksheet.sc",
    expectedContent = "",
  )

  checkScala("new-scala-script")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(ScalaScript),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = "",
  )

  checkScala("new-scala-script-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(ScalaScript),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = "",
  )

  checkScala("new-ammonite-script-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Left(ScalaScript),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/Foo.sc",
    expectedContent = "",
  )

  checkScala("new-class")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(Class),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-class-backticked")(
    directory = Some("a/src/main/scala/this/"),
    fileType = Right(Class),
    fileName = Right("type"),
    expectedFilePath = "a/src/main/scala/this/type.scala",
    expectedContent = s"""|package `this`
                          |
                          |class `type` {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-class-name-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(Class),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-class-fully-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Left(Class),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |class Foo {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-case-class")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(CaseClass),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin,
  )

  checkScala("new-case-class-name-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Right(CaseClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin,
  )

  checkScala("new-case-class-fully-provided")(
    directory = Some("a/src/main/scala/foo/"),
    fileType = Left(CaseClass),
    fileName = Left("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = """|package foo
                         |
                         |final case class Foo()
                         |""".stripMargin,
  )

  checkScala("new-object-null-dir")(
    directory = None,
    fileType = Right(Object),
    fileName = Right("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-object-null-dir-name-provided")(
    directory = None,
    fileType = Right(Object),
    fileName = Left("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-object-null-dir")(
    directory = None,
    fileType = Left(Object),
    fileName = Left("Bar"),
    expectedFilePath = "Bar.scala",
    expectedContent = s"""|object Bar {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-trait-new-dir")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Right("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-trait-new-dir-name-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Left("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-trait-new-dir-fully-provided")(
    directory = Some("a/src/main/scala/"),
    fileType = Right(Trait),
    fileName = Right("bar/Baz"),
    expectedFilePath = "a/src/main/scala/bar/Baz.scala",
    expectedContent = s"""|package bar
                          |
                          |trait Baz {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-package-object")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(PackageObject),
    fileName = Right(
      ""
    ), // Just given an empty string here because it will never be used for package objects
    expectedFilePath = "a/src/main/scala/foo/package.scala",
    expectedContent = s"""|package object foo {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-package-object-provided")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Left(PackageObject),
    fileName = Right(
      ""
    ), // Just given an empty string here because it will never be used for package objects
    expectedFilePath = "a/src/main/scala/foo/package.scala",
    expectedContent = s"""|package object foo {
                          |$indent
                          |}
                          |""".stripMargin,
  )

  checkScala("new-class-on-file")(
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
                       |""".stripMargin,
  )

  checkScala("new-class-on-file-name-provided")(
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
                       |""".stripMargin,
  )

  checkScala("new-class-on-file-fully-provided")(
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
                       |""".stripMargin,
  )

  checkScala("existing-file")(
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
    expectedException = List(classOf[FileAlreadyExistsException]),
  )

  checkScala("scala3-enum")(
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
    scalaVersion = Some(V.scala3),
  )

  checkScala("empty-file-with-package")(
    directory = Some("a/src/main/scala/foo"),
    fileType = Right(ScalaFile),
    fileName = Right("Foo"),
    expectedFilePath = "a/src/main/scala/foo/Foo.scala",
    expectedContent = s"""|package foo
                          |
                          |
                          |""".stripMargin,
  )

  checkJava("new-java-class")(
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
  )

  checkJava("new-java-class-name-provided")(
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
  )

  checkJava("new-java-class-fully-provided")(
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
  )

  checkJava("new-java-interface")(
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
  )

  checkJava("new-java-interface-name-provided")(
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
  )

  checkJava("new-java-interface-fully-provided")(
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
  )

  checkJava("new-java-enum")(
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
  )

  checkJava("new-java-enum-name-provided")(
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
  )

  checkJava("new-java-enum-fully-provided")(
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
  )

  checkJava("new-java-record")(
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
    javaMinVersion = Some("14"),
  )

  checkJava("new-java-record-name-provided")(
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
    javaMinVersion = Some("14"),
  )

  checkJava("new-java-record-fully-provided")(
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
    javaMinVersion = Some("14"),
  )

  private lazy val indent = "  "

  type ProvidedFileType = NewFileType
  type PickedFileType = NewFileType
  type Provided = String
  type Picked = String

  private def checkJava(testName: TestOptions)(
      directory: Option[String],
      fileType: Either[ProvidedFileType, PickedFileType],
      fileName: Either[Provided, Picked],
      expectedFilePath: String,
      expectedContent: String,
      existingFiles: String = "",
      expectedException: List[Class[_]] = Nil,
      javaMinVersion: Option[String] = None,
  )(implicit loc: Location): Unit = if (
    Properties.isJavaAtLeast(javaMinVersion.getOrElse("1.8"))
  )
    check(testName)(
      directory,
      fileType,
      fileName,
      expectedFilePath,
      expectedContent,
      ServerCommands.NewJavaFile,
      existingFiles,
      expectedException,
      None,
    )

  private def checkScala(testName: TestOptions)(
      directory: Option[String],
      fileType: Either[ProvidedFileType, PickedFileType],
      fileName: Either[Provided, Picked],
      expectedFilePath: String,
      expectedContent: String,
      existingFiles: String = "",
      expectedException: List[Class[_]] = Nil,
      scalaVersion: Option[String] = None,
  )(implicit loc: Location): Unit = check(testName)(
    directory,
    fileType,
    fileName,
    expectedFilePath,
    expectedContent,
    ServerCommands.NewScalaFile,
    existingFiles,
    expectedException,
    scalaVersion,
  )

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
      command: ListParametrizedCommand[String],
      existingFiles: String,
      expectedException: List[Class[_]],
      scalaVersion: Option[String],
  )(implicit loc: Location): Unit =
    test(testName) {
      val localScalaVersion = scalaVersion.getOrElse(V.scala213)
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
        fileType.fold(ft => ft.id, _ => null.asInstanceOf[String]),
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
        _ <- server.executeCommand(command, args: _*)
        _ = {
          assertNoDiff(
            client.workspaceMessageRequests,
            expectedMessages,
          )
          assert(expectedFilePathAbsolute.exists)
          assertLines(
            expectedFilePathAbsolute.readText,
            expectedContent,
          )
        }
      } yield ()

      futureToRecover
        .recover {
          case e if expectedException.contains(e.getClass()) =>
            assertNoDiff(
              expectedFilePathAbsolute.readText,
              expectedContent,
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
