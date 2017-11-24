package tests.compiler

import scala.meta.languageserver.compiler.HoverProvider
import play.api.libs.json.Json

object HoverTest extends CompilerSuite {

  def check(
      filename: String,
      code: String,
      expectedValue: String
  ): Unit = {
    targeted(
      filename,
      code, { pos =>
        val result = HoverProvider.hover(compiler, pos)
        val obtained = Json.prettyPrint(Json.toJson(result))
        val expected = s"""{
           |  "contents" : [ {
           |    "language" : "scala",
           |    "value" : "$expectedValue"
           |  } ]
           |}""".stripMargin
        assertNoDiff(obtained, expected)
      }
    )
  }

  check(
    "val assignment",
    """
      |object a {
      |  val <<x>> = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "List[Some[Int]]"
  )

  check(
    "val assignment type annotation",
    """
      |object a {
      |  val <<x>>: List[Option[Int]] = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "List[Option[Int]]"
  )

  check(
    "var assignment",
    """
      |object a {
      |  var <<x>> = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "List[Some[Int]]"
  )

  check(
    "var assignment type annotation",
    """
      |object a {
      |  var <<x>>: List[Option[Int]] = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "List[Option[Int]]"
  )

  check(
    "select",
    """
      |object a {
      |  val x = List(1, 2, 3)
      |  <<x>>.mkString
      |}
    """.stripMargin,
    "List[Int]"
  )

  check(
    "literal Int",
    """
      |object a {
      |  val x = <<42>>
      |}
    """.stripMargin,
    "Int"
  )

  check(
    "case class apply",
    """
      |object a {
      |  case class User(name: String, age: Int)
      |  val user = <<User>>("test", 42)
      |}
    """.stripMargin,
    "(name: String, age: Int)a.User"
  )

  check(
    "case class parameters",
    """
      |object a {
      |  case class User(<<name>>: String, age: Int)
      |}
    """.stripMargin,
    "String"
  )

  check(
    "def",
    """
      |object a {
      |  def <<test>>(x: String, y: List[Int]) = y.mkString.length
      |}
    """.stripMargin,
    "(x: String, y: List[Int])Int"
  )

  check(
    "def call site",
    """
      |object a {
      |  def test(x: String, y: List[Int]) = y.mkString.length
      |  <<test>>("foo", Nil)
      |}
    """.stripMargin,
    "(x: String, y: List[Int])Int"
  )

}
