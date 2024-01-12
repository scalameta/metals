package tests.pc

import tests.BaseCompletionSuite

class CompletionScala3Suite extends BaseCompletionSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  check(
    "issue-3625".tag(IgnoreScalaVersion.forLessThan("3.1.3-RC1")),
    """|package a
       |
       |object Test:
       |  case class Widget(name: String, other: Int)
       |  val otxxx: Int = 1
       |  Widget(name = "foo", @@
       |""".stripMargin,
    """|other = : Int
       |other = otxxx : Int
       |otxxx: Int
       |""".stripMargin,
    topLines = Some(3)
  )

  check(
    "constructor-empty",
    """|package a
       |
       |class Testing
       |
       |def main =
       |  Testin@@
       |""".stripMargin,
    """|Testing a
       |Testing(): Testing
       |""".stripMargin
  )

  check(
    "constructor-params",
    """|package a
       |
       |class Testing(a: Int, b: String)
       |
       |def main =
       |  Testin@@
       |""".stripMargin,
    """|Testing a
       |Testing(a: Int, b: String): Testing
       |""".stripMargin
  )

  // https://github.com/scalameta/metals/issues/2810
  check(
    "higher-kinded-match-type".tag(IgnoreScalaVersion.forLessThan("3.1.3-RC2")),
    """|package a
       |
       |trait Foo[A] {
       |  def map[B](f: A => B): Foo[B] = ???
       |}
       |case class Bar[F[_]](bar: F[Int])
       |type M[T] = T match {
       |  case Int => Foo[Int]
       |}
       |object Test:
       |  val x = Bar[M](new Foo[Int]{})
       |  x.bar.m@@
       |""".stripMargin,
    """|map[B](f: A => B): Foo[B]
       |""".stripMargin,
    topLines = Some(1)
  )

  checkEdit(
    "trait-member",
    """|trait Foo:
       |  def foo: String
       |
       |object Bar extends Foo:
       |  def@@
       |""".stripMargin,
    """|trait Foo:
       |  def foo: String
       |
       |object Bar extends Foo:
       |  def foo: String = ${0:???}
       |""".stripMargin,
    assertSingleItem = false
  )

  check(
    "multi-export".tag(
      IgnoreScalaVersion.forLessThan("3.2.2")
    ),
    """|export scala.collection.{AbstractMap, Set@@}
       |""".stripMargin,
    """|Set scala.collection
       |SetOps scala.collection
       |""".stripMargin
  )
}
