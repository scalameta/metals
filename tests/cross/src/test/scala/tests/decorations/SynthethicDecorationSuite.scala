package tests.decorations

import tests.BaseSyntheticDecorationsSuite

class SynthethicDecorationSuite extends BaseSyntheticDecorationsSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

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
  )

  check(
    "using-param",
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
  )

  check(
    "given-conversion",
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
  )
}
