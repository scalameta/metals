package tests.hover

import tests.BuildInfoVersions
import tests.pc.BaseHoverSuite

class HoverScala3TypeSuite extends BaseHoverSuite {

  override protected def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala2Versions.toSet

  check(
    "union",
    """
      |import java.nio.file._
      |case class Foo(x: Int)
      |case class Bar[T](x: T)
      |object a {
      |  val name: Foo | Bar[Files] = Foo(1)
      |  <<na@@me>>
      |}
      |""".stripMargin,
    """|val name: Foo | Bar[Files]
       |""".stripMargin.hover
  )

  check(
    "intersection",
    """
      |import java.nio.file._
      |
      |trait Resettable:
      |  def reset(): Unit
      |
      |trait Growable[T]:
      |  def add(t: T): Unit
      |
      |def f(arg: Resettable & Growable[Files]) = {
      |  <<ar@@g.reset()>>
      |}
      |""".stripMargin,
    """|arg: Resettable & Growable[Files]
       |""".stripMargin.hover
  )

  // We should produce a shorter type but:
  // https://github.com/lampepfl/dotty/issues/11683
  check(
    "enums",
    """|
       |object SimpleEnum:
       |  enum Color:
       |   case <<Re@@d>>, Green, Blue
       |
       |""".stripMargin,
    """|case Red: enums.SimpleEnum.Color.Red
       |""".stripMargin.hover
  )

  check(
    "enum-params",
    """|
       |object SimpleEnum:
       |  enum Color:
       |    case <<Gr@@een>> extends Color(2)
       |    case Red extends Color(1)
       |    case Blue extends Color(3)
       |
       |
       |""".stripMargin,
    """|case Green: Color
       |""".stripMargin.hover
  )

  // https://github.com/scalameta/metals/issues/1991
  check(
    "extension-methods",
    """|
       |object Foo:
       |    extension (s: String):
       |        def double = s + s
       |        def double2 = s + s        
       |    end extension
       |    "".<<doub@@le2>>
       |end Foo
       |""".stripMargin,
    ""
  )

  // TODO: better printing for using
  // currently "def apply[T](a: T)(implicit x$2: Int): T"
  check(
    "using".fail,
    """
      |object a {
      |  def apply[T](a: T)(using Int): T = ???
      |  implicit val ev = 1
      |  <<ap@@ply("test")>>
      |}
      |""".stripMargin,
    """|def apply[T](a: T)(using: Int): T
       |""".stripMargin.hover
  )
}
