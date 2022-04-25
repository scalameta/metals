package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.CreateCompanionObjectCodeAction
import scala.meta.internal.metals.codeactions.ExtractRenameMember
import scala.meta.internal.metals.codeactions.ExtractValueCodeAction
import scala.meta.internal.metals.codeactions.InsertInferredType
import scala.meta.internal.metals.codeactions.SourceOrganizeImports
import scala.meta.internal.mtags.MtagsEnrichments.XtensionAbsolutePath

import munit.Location
import munit.TestOptions
import tests.codeactions.BaseCodeActionLspSuite

class Scala3CodeActionLspSuite
    extends BaseCodeActionLspSuite("cross-code-actions") {

  override protected val scalaVersion: String = BuildInfo.scala3

  checkNoAction(
    "val",
    """|package a
       |
       |object A {
       |  val al<<>>pha: Int = 123
       |}
       |""".stripMargin
  )

  check(
    "organize-imports",
    """
      |package a
      |import scala.concurrent.{ExecutionContext, Future}
      |import scala.util.Try<<>>
      |
      |object A {
      |  val executionContext: ExecutionContext = ???
      |  val k = Future.successful(1)
      |  val tr = Try{ new Exception("name") }
      |}
      |""".stripMargin,
    s"${SourceOrganizeImports.title}",
    """|package a
       |import scala.concurrent.ExecutionContext
       |import scala.concurrent.Future
       |import scala.util.Try
       |
       |object A {
       |  val executionContext: ExecutionContext = ???
       |  val k = Future.successful(1)
       |  val tr = Try{ new Exception("name") }
       |}
       |""".stripMargin,
    kind = List(SourceOrganizeImports.kind)
  )

  checkExtractedMember(
    "extract-enum",
    """|package a
       |
       |case class A()
       |
       |enum <<Color>>(val rgb: Int):
       |   case Red   extends Color(0xFF0000)
       |   case Green extends Color(0x00FF00)
       |   case Blue  extends Color(0x0000FF)
       |end Color
       |""".stripMargin,
    s"""|${ExtractRenameMember.title("enum", "Color")}""".stripMargin,
    """|package a
       |
       |case class A()
       |
       |""".stripMargin,
    newFile = (
      "Color.scala",
      s"""|package a
          |
          |enum Color(val rgb: Int):
          |   case Red   extends Color(0xFF0000)
          |   case Green extends Color(0x00FF00)
          |   case Blue  extends Color(0x0000FF)
          |end Color
          |""".stripMargin
    )
  )

  check(
    "val-pattern",
    """|package a
       |
       |object A:
       |  val (fir<<>>st, second) = (List(1), List(""))
       |""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|package a
       |
       |object A:
       |  val (first: List[Int], second) = (List(1), List(""))
       |""".stripMargin
  )

  check(
    "auto-import",
    """|package a
       |
       |object A:
       |  var al<<>>pha = List(123).toBuffer
       |
       |""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.Buffer
       |
       |object A:
       |  var alpha: Buffer[Int] = List(123).toBuffer
       |""".stripMargin
  )

  check(
    "single-def",
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = method2(i + 23 + <<123>>)
       |  }
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  def main = {
       |    def inner(i : Int) = {
       |      val newValue = i + 23 + 123
       |      method2(newValue)
       |    }
       |  }
       |}
       |""".stripMargin
  )

  check(
    "single-def-optional",
    """|object Main:
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) = method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main:
       |  def method2(i: Int) = ???
       |  def main =
       |    def inner(i : Int) =
       |      val newValue = i + 23 + 123
       |      method2(newValue)
       |""".stripMargin
  )

  check(
    "single-def-split",
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |    method2(i + 23 + <<123>>)
       |}
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main {
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) = {
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |  }
       |}
       |""".stripMargin
  )

  check(
    "single-def-split-optional",
    """|object Main:
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |  method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|object Main:
       |  def method2(i: Int) = ???
       |  
       |  def main(i : Int) =
       |    val newValue = i + 23 + 123
       |    method2(newValue)
       |
       |""".stripMargin
  )

  check(
    "single-toplevel-optional",
    """|
       |def method2(i: Int) = {
       |  val a = 1
       |  a + 2
       |}
       |  
       |def main(i : Int) = method2(i + 23 + <<123>>)
       |
       |""".stripMargin,
    ExtractValueCodeAction.title,
    """|def method2(i: Int) = {
       |  val a = 1
       |  a + 2
       |}
       |  
       |def main(i : Int) = {
       |  val newValue = i + 23 + 123
       |  method2(newValue)
       |}
       |""".stripMargin
  )

  check(
    "insert-companion-object-of-braceless-enum-inside-parent-object",
    """|object Baz:
       |  enum F<<>>oo:
       |    case a
       |    def fooMethod(): Unit = {
       |      val a = 3
       |    }
       |  end Foo
       |
       |  class Bar {}
       |""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|object Baz:
       |  enum Foo:
       |    case a
       |    def fooMethod(): Unit = {
       |      val a = 3
       |    }
       |  end Foo
       |
       |  object Foo:
       |    ???
       |
       |  class Bar {}
       |""".stripMargin
  )

  check(
    "insert-companion-object-of-braceless-case-class-file-end",
    """|case class F<<>>oo(a: Int):
       |  def b = a""".stripMargin,
    s"""|${CreateCompanionObjectCodeAction.companionObjectCreation}
        |""".stripMargin,
    """|case class Foo(a: Int):
       |  def b = a
       |
       |object Foo:
       |  ???
       |
       |""".stripMargin,
    fileName = "Foo.scala"
  )

  def checkExtractedMember(
      name: TestOptions,
      input: String,
      expectedActions: String,
      expectedCode: String,
      newFile: (String, String),
      selectedActionIndex: Int = 0
  )(implicit loc: Location): Unit = {
    check(
      name,
      input,
      expectedActions,
      expectedCode,
      selectedActionIndex,
      extraOperations = {
        val (fileName, content) = newFile
        val absolutePath = workspace.resolve(getPath(fileName))
        assert(
          absolutePath.exists,
          s"File $absolutePath should have been created"
        )
        assertNoDiff(absolutePath.readText, content)
      }
    )
  }

  private def getPath(name: String) = s"a/src/main/scala/a/$name"

}
