package tests.pc

import tests.BaseCompletionSuite

class CompletionIssueSuite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  check(
    "mutate",
    """package a
      |class Foo@@
      |""".stripMargin,
    ""
  )

  check(
    "issue-569",
    """package a
      |class Main {
      |  new Foo@@
      |}
    """.stripMargin,
    ""
  )

  check(
    "issue-749",
    """package a
      |trait Observable[+A] {
      |  type Self[+T] <: Observable[T]
      |}
      |trait EventStream[+A] extends Observable[A] {
      |  override type Self[+T] = EventStream[T]
      |}
      |class Main {
      |  val stream: EventStream[Int] = ???
      |  stream.@@
      |}
      |""".stripMargin,
    "Self[+T] = Main.this.stream.Self",
    topLines = Some(1)
  )

  checkEdit(
    "issue-753",
    """
      |package a
      |object A {
      |  object Nested{
      |    object NestedLeaf
      |  }
      |}
      |object B {
      |  NestedLea@@
      |}""".stripMargin,
    """|package a
       |object A {
       |  object Nested{
       |    object NestedLeaf
       |  }
       |}
       |object B {
       |  A.Nested.NestedLeaf
       |}
       |""".stripMargin
  )

  checkEdit(
    "issue-783",
    """
      |package all
      |import all.World.Countries.{
      |  Sweden,
      |  USA
      |}
      |
      |object World {
      |  object Countries{
      |    object Sweden
      |    object Norway
      |    object France
      |    object USA
      |  }
      |}
      |import all.World.Countries.France
      |object B {
      |  val allCountries = Sweden + France + USA + Norway@@
      |}""".stripMargin,
    """|package all
       |import all.World.Countries.{
       |  Sweden,
       |  USA
       |}
       |
       |object World {
       |  object Countries{
       |    object Sweden
       |    object Norway
       |    object France
       |    object USA
       |  }
       |}
       |import all.World.Countries.France
       |object B {
       |  val allCountries = Sweden + France + USA + World.Countries.Norway
       |}
       |""".stripMargin
  )

  check(
    "issue-813-empty",
    """|package a
       |
       |object Main {
       |  (1 to 10).toList
       |  .map(_ + 1) // comment breaks completions
       |  .@@
       |}
       |""".stripMargin,
    """|::[B >: Int](elem: B): List[B]
       |:::[B >: Int](prefix: List[B]): List[B]
       |""".stripMargin,
    topLines = Some(2),
    compat = Map(
      "2.12" ->
        """|++[B >: Int, That](that: GenTraversableOnce[B])(implicit bf: CanBuildFrom[List[Int],B,That]): That
           |+:[B >: Int, That](elem: B)(implicit bf: CanBuildFrom[List[Int],B,That]): That
           |""".stripMargin
    )
  )

  check(
    "issue-813",
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) // comment breaks completions
       |  .fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "issue-813-space",
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) // comment breaks completions
       |  . fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2)
  )

  check(
    "issue-813-multi",
    """|package a
       |
       |object Main {
       |  Array(1, 1,10)
       |  .map(_ + 1) /* comment breaks completions */
       |  .fil@@
       |}
       |""".stripMargin,
    """|filter(p: Int => Boolean): Array[Int]
       |filterNot(p: Int => Boolean): Array[Int]
       |""".stripMargin,
    topLines = Some(2)
  )

  checkEdit(
    "issue-1281-import-parens",
    """object obj {
      |  def method(arg: String): Unit = ()
      |}
      |import obj.meth@@
      |""".stripMargin,
    """object obj {
      |  def method(arg: String): Unit = ()
      |}
      |import obj.method""".stripMargin
  )

  override val compatProcess: Map[String, String => String] = Map(
    "2.13" -> { s =>
      s.replace(
        "::[B >: Int](x: B): List[B]",
        "::[B >: Int](elem: B): List[B]"
      )
    }
  )
}
