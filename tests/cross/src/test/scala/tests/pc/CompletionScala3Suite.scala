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
       |otxxx: Int
       |""".stripMargin,
    topLines = Some(2)
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
}
