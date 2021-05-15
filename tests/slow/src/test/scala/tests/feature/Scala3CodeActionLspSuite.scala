package tests.feature

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.codeactions.ExtractRenameMember
import scala.meta.internal.metals.codeactions.OrganizeImports
import scala.meta.internal.metals.codeactions.InsertInferredType
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
    s"${OrganizeImports.title}",
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
    kind = List(OrganizeImports.kind)
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
