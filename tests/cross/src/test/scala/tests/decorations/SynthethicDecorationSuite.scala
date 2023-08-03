package tests.decorations

import tests.BaseSyntheticDecorationsSuite
import scala.meta.internal.pc.DecorationKind

class SynthethicDecorationSuite extends BaseSyntheticDecorationsSuite {

  check(
    "type-params",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(List(1))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello[List[Int]](List[Int](1))
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.TypeParameter),
  )

  check(
    "type-params2",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(Map((1,"abc")))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello[Map[Int,String]](Map[String][Int]((1,"abc")))
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def hello[T](t: T) = t
           |  val x = hello[Map[Int, String]](Map[String][Int]((1,"abc")))
           |}
           |""".stripMargin
    ),
    kind = Some(DecorationKind.TypeParameter),
  )

  check(
    "implicit-param",
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(implicit one: Int) = x + one
       |  val x = addOne(1)
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(implicit one: Int) = x + one
       |  val x = addOne(1)(imp)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitParameter),
  )

  check(
    "implicit-conversion",
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = User(x.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = User(x.toString)
       |  val y: User = intToUser(1)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitConversion),
  )

  check(
    "using-param".tag(IgnoreScala2),
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(using one: Int) = x + one
       |  val x = addOne(1)
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(using one: Int) = x + one
       |  val x = addOne(1)(imp)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitParameter),
  )

  check(
    "given-conversion".tag(IgnoreScala2),
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = intToUser(1)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitConversion),
  )
}
