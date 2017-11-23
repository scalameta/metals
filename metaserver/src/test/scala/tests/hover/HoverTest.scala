package tests.compiler

import scala.meta.languageserver.Compiler
import scala.reflect.runtime.universe._
import scala.reflect.runtime.currentMirror
import scala.tools.nsc.interactive.Global
import scala.tools.reflect.ToolBox

import tests.MegaSuite
import utest._

object HoverTest extends CompilerSuite {

  def check(
      filename: String,
      code: String,
      expected: String
  ): Unit = {
    targeted(
      filename,
      code, { pos =>
        val response =
          Compiler.ask[compiler.Tree](r => compiler.askTypeAt(pos, r))
        val typedTree = response.get.swap.toOption
        val maybeObtained =
          typedTree.flatMap(t => Compiler.typeOfTree(compiler)(t))
        Predef.assert(maybeObtained.isDefined, s"No type information at ${pos}")
        val obtained = maybeObtained.get
        assert(obtained == expected)
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

}
