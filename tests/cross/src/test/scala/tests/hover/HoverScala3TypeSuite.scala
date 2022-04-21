package tests.hover

import tests.pc.BaseHoverSuite

class HoverScala3TypeSuite extends BaseHoverSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

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
    """|case Red: Red
       |""".stripMargin.hover,
    compat = Map(
      ">=3.1.3-RC1-bin-20220301-fae7c09-NIGHTLY" ->
        """|case Red: Color
           |""".stripMargin.hover
    )
  )

  check(
    "enums2",
    """|
       |object SimpleEnum:
       |  enum <<Col@@or>>:
       |   case Red, Green, Blue
       |
       |""".stripMargin,
    """|enum Color: enums2.SimpleEnum
       |""".stripMargin.hover
  )

  check(
    "enums-outermost",
    """|enum Color:
       |  case Red
       |  case <<Bl@@ue>>
       |  case Cyan
       |""".stripMargin,
    "",
    compat = Map(
      ">=3.1.3-RC1-bin-20220301-fae7c09-NIGHTLY" ->
        """|case Blue: Color
           |""".stripMargin.hover
    )
  )

  check(
    "enums3",
    """|
       |object SimpleEnum:
       |  enum Color:
       |    case Red, Green, Blue
       |  val color = <<Col@@or>>.Red
       |
       |""".stripMargin,
    """|enum Color: enums3.SimpleEnum
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

  check(
    "extension-methods",
    """|
       |object Foo:
       |    extension (s: String)
       |        def double = s + s
       |        def double2 = s + s        
       |    end extension
       |    "".<<doub@@le2>>
       |end Foo
       |""".stripMargin,
    "extension (s: String) def double2: String".hover
  )

  /* Currently there is no way to differentiate between
   * trailing using params in extension parameter and the
   * starting using params for the actual method.
   * As user can actually supply params to them by hand when
   * invoking the extension method, we always show them next to the
   * method itself.
   * https://github.com/lampepfl/dotty/issues/13123
   */
  check(
    "extension-methods-complex",
    """|class A
       |class B
       |class C
       |object Foo:
       |    extension [T](using A)(s: T)(using B)
       |        def double[G](using C)(times: G) = (s.toString + s.toString) * times
       |    end extension
       |    given A with {}
       |    given B with {}
       |    given C with {}
       |    "".<<doub@@le(1)>>
       |end Foo
       |""".stripMargin,
    "extension [T](using A)(s: T) def double(using B)[G](using C)(times: G): String".hover
  )

  check(
    "extension-methods-complex-binary",
    """|class A
       |class B
       |class C
       |
       |object Foo:
       |    extension [T](using A)(main: T)(using B)
       |      def %:[R](res: R)(using C): R = ???
       |    given A with {}
       |    given B with {}
       |    given C with {}
       |    val c = C()
       |    "" <<%@@:>> 11
       |end Foo
       |""".stripMargin,
    """|Int
       |extension [T](using A)(main: T) def %:[R](res: R)(using B)(using C): R""".stripMargin.hover
  )

  check(
    "using",
    """
      |object a {
      |  def apply[T](a: T)(using Int): T = ???
      |  implicit val ev = 1
      |  <<ap@@ply("test")>>
      |}
      |""".stripMargin,
    """|String
       |def apply[T](a: T)(using Int): T
       |""".stripMargin.hover
  )

  check(
    "toplevel-left",
    """|def foo = <<L@@eft>>("")
       |""".stripMargin,
    """|Left[String, Nothing]
       |def apply[A, B](value: A): Left[A, B]
       |""".stripMargin.hover
  )
}
