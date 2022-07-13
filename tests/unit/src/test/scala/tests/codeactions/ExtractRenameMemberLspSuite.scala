package tests.codeactions

import java.nio.file.Paths

import scala.meta.inputs.Input
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.MetalsEnrichments.XtensionAbsolutePathBuffers
import scala.meta.internal.metals.codeactions.ExtractRenameMember
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.mtags.MtagsEnrichments.XtensionAbsolutePath
import scala.meta.internal.mtags.MtagsEnrichments.XtensionMetaPosition
import scala.meta.io.AbsolutePath

import munit.Location
import munit.TestOptions
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import tests.TreeUtils

class ExtractRenameMemberLspSuite
    extends BaseCodeActionLspSuite("extractClass") {

  val indent = "  "

  checkActionProduced(
    "extends-sealed-trait-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |sealed trait MyTrait
       |case class <<B>>() extends MyTrait
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "with-sealed-trait-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |trait MyTrait
       |sealed trait MySealedTrait
       |case class <<B>>() extends MyTrait with MySealedTrait
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "with-sealed-trait-in-object-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |object MyObject {
       | sealed trait MySealedTrait
       |}
       |
       |trait MyTrait
       |case class <<B>>() extends MyTrait with MyObject.MySealedTrait
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "extends-sealed-class-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |sealed class MyTrait
       |case class <<B>>() extends MyTrait
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "with-sealed-class-and-trait-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |trait MyTrait
       |sealed class MySealedTrait
       |case class <<B>>() extends MySealedTrait with MyTrait
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "sealed-trait-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |sealed trait <<MySealedTrait>>
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "companion-of-sealed-class-no-codeAction",
    """|package a
       |
       |case class A()
       |
       |sealed case class MySealedClass
       |
       |object <<MySealedClass>> {}
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "no-name-selection-no-codeAction",
    """|package a
       |
       |case <<cl>>ass A()
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "inner-class-no-codeAction",
    """|package a
       |
       |case class A() {
       |  case class <<B>>()
       |}
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "sealed-class-no-codeAction",
    """|package a
       |
       |sealed case class <<A>>()
       |
       |""".stripMargin,
    codeActionExpected = false,
  )

  checkActionProduced(
    "extract-class-extended",
    """|package a
       |
       |abstract class A()
       |class <<B>>() extends A
       |""".stripMargin,
  )

  checkActionProduced(
    "extract-class-inner-package",
    """|package a
       |
       |case class A()
       |
       |package b {
       |  case class <<B>>()
       |}
       |""".stripMargin,
  )

  checkActionProduced(
    "extract-class-inner-package-no-subpackages",
    """|package a
       |
       |case class A()
       |
       |package b {
       |  case class <<B>>()
       |  package c {
       |    case class C()
       |  }
       |}
       |""".stripMargin,
  )

  checkActionProduced(
    "extract-class-inner-subpackage",
    """|package a
       |
       |case class A()
       |
       |package b {
       |  case class B()
       |  package c {
       |    case class <<C>>()
       |  }
       |}
       |""".stripMargin,
  )
  checkActionProduced(
    "extract-object-inner-package",
    """|package a
       |
       |case class A()
       |
       |package b {
       |  object <<B>>{}
       |}
       |
       |package c {
       |  case class C()
       |}
       |""".stripMargin,
  )

  checkActionProduced(
    "extract-object-inner-package",
    """|package a
       |
       |case class A()
       |
       |package b {
       |  object <<B>>{}
       |}
       |
       |package c {
       |  case class C()
       |}
       |""".stripMargin,
  )

  checkActionProduced(
    "extract-class-with-imports",
    """|package a
       |import scala.io.Source
       |
       |case class A()
       |case class <<B>>() {
       |  val s = Source.fromFile("A.scala")
       |}
       |""".stripMargin,
  )

  checkNoAction(
    "same-name-no-codeAction",
    """|package a
       |
       |case class <<A>>()
       |""".stripMargin,
  )

  val renameCodeActionTitle: String = ExtractRenameMember
    .renameFileAsClassTitle(fileName = "A.scala", memberName = "MyClass")

  checkFileRenamed(
    "only-one-class-with-inner-class-rename-file",
    """|package a
       |
       |case class <<MyClass>>() {
       |  case class B()
       |}
       |
       |""".stripMargin,
    renameCodeActionTitle,
    newFileName = "MyClass.scala",
  )

  checkFileRenamed(
    "only-one-class-rename-file",
    """|package a
       |
       |case class <<MyClass>>() {
       |}
       |
       |""".stripMargin,
    renameCodeActionTitle,
    newFileName = "MyClass.scala",
  )

  checkExtractedMember(
    "extract-class",
    """|package a
       |
       |case class A()
       |class <<B>>()
       |""".stripMargin,
    expectedActions = ExtractRenameMember.title("class", "B"),
    """|package a
       |
       |case class A()
       |""".stripMargin,
    fileName = "A.scala",
    newFile = (
      "B.scala",
      s"""|package a
          |
          |class B()
          |""".stripMargin,
    ),
  )

  checkExtractedMember(
    "extract-class-without-non-in-scope-imports",
    """|package a
       |import scala.io.Source
       |
       |case class A() {
       |  import scala.concurrent.Future
       |}
       |case class <<B>>() {
       |  val s = Source.fromFile("A.scala")
       |}
       |""".stripMargin,
    expectedActions = ExtractRenameMember.title("class", "B"),
    """|package a
       |import scala.io.Source
       |
       |case class A() {
       |  import scala.concurrent.Future
       |}
       |""".stripMargin,
    fileName = "A.scala",
    newFile = (
      "B.scala",
      s"""|package a
          |
          |import scala.io.Source
          |
          |case class B() {
          |  val s = Source.fromFile("A.scala")
          |}
          |""".stripMargin,
    ),
  )

  checkExtractedMember(
    "extract-class-inner-subpackage-with-imports",
    """|package a
       |
       |import scala.io.Source
       |
       |case class A()
       |
       |package b {
       |  import scala.io.Codec
       |  case class B()
       |  package c {
       |    case class <<C>>()
       |  }
       |}
       |""".stripMargin,
    expectedActions = ExtractRenameMember.title("class", "C"),
    s"""|package a
        |
        |import scala.io.Source
        |
        |case class A()
        |
        |package b {
        |  import scala.io.Codec
        |  case class B()
        |${indent}
        |}
        |""".stripMargin,
    fileName = "A.scala",
    newFile = (
      "C.scala",
      s"""|package a.b.c
          |
          |import scala.io.Source
          |import scala.io.Codec
          |
          |case class C()
          |""".stripMargin,
    ),
  )

  def checkExtractedMember(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      newFile: (String, String),
      selectedActionIndex: Int = 0,
      fileName: String = "A.scala",
  )(implicit loc: Location): Unit = {
    check(
      name,
      input,
      expectedActions,
      expectedCode,
      selectedActionIndex,
      extraOperations = {
        val (fileName, content) = newFile
        val absolutePath = workspace.resolve(testedFilePath(fileName))
        assert(
          absolutePath.exists,
          s"File $absolutePath should have been created",
        )
        assertNoDiff(absolutePath.readText, content)
      },
      fileName = fileName,
    )
  }

  def checkFileRenamed(
      name: TestOptions,
      input: String,
      expectedActions: String,
      newFileName: String,
      selectedActionIndex: Int = 0,
  )(implicit loc: Location): Unit = {
    val renamedPath = testedFilePath(newFileName)
    check(
      name,
      input,
      expectedActions,
      expectedCode = "",
      selectedActionIndex,
      renamePath = Some(renamedPath),
      extraOperations = {
        val oldAbsolutePath = workspace.resolve("a/src/main/scala/a/A.scala")
        assert(
          !oldAbsolutePath.exists,
          s"File $oldAbsolutePath should have been renamed",
        )
      },
    )

  }

  def checkActionProduced(
      name: TestOptions,
      original: String,
      codeActionExpected: Boolean = true,
      scalaVersion: String = V.scala213,
      fileName: String = "A.scala",
  ): Unit =
    test(name) {
      val (buffers, trees) = TreeUtils.getTrees(scalaVersion)
      val filename = fileName
      val path = AbsolutePath(Paths.get(filename))
      val startOffset = original.indexOf("<<")
      val endOffset = original.indexOf(">>")
      val sourceText =
        original
          .replace("<<", "")
          .replace(">>", "")
      val input = Input.VirtualFile(filename, sourceText)
      val pos = scala.meta.Position
        .Range(input, startOffset, endOffset - "<<".length())
        .toLsp
      val extractRenameMember = new ExtractRenameMember(trees, client)
      buffers.put(path, sourceText)
      val textDocumentIdentifier = path.toTextDocumentIdentifier
      val codeActionParams = new CodeActionParams(
        textDocumentIdentifier,
        pos,
        new CodeActionContext(),
      )
      val cancelToken = EmptyCancelToken

      val codeActionFut =
        extractRenameMember.contribute(codeActionParams, cancelToken)

      for {
        codeActions <- codeActionFut
        _ = {
          if (codeActionExpected) assert(codeActions.nonEmpty)
          else assert(codeActions.isEmpty)
        }
      } yield ()
    }

  // Same structure of path as in function `check`
  private def testedFilePath(name: String) = s"a/src/main/scala/a/$name"
}
