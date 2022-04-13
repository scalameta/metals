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
}
