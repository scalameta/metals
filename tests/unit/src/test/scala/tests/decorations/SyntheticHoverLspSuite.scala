package tests.decorations

import tests.BaseQuickBuildSuite

class SyntheticHoverLspSuite extends BaseQuickBuildSuite("implicits") {

  test("hovers") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |
           |/a/src/main/scala/com/example/Main.scala
           |package com.example
           |import scala.concurrent.Future
           |case class Location(city: String)
           |object Main{
           |  def hello()(implicit name: String, from: Location) = {
           |    println(s"Hello $$name from $${from.city}")
           |  }
           |  implicit val andy : String = "Andy"
           |
           |  def greeting() = {
           |    implicit val boston = Location("Boston")
           |    hello()
           |  }
           |  
           |  "foo".map(c => c.toUpper)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Main.scala")
      _ <- server.didSave("a/src/main/scala/com/example/Main.scala")(identity)
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/com/example/Main.scala",
        "    hello()@@",
        """|**With synthetics added**:
           |```scala
           |hello()(com.example.Main.andy, boston)
           |```
           |""".stripMargin
      )
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/com/example/Main.scala",
        "  \"foo\".map(c @@=> c.toUpper)",
        """|**With synthetics added**:
           |```scala
           |scala.Predef.augmentString("foo").map[scala.Char, scala.Predef.String](c => scala.LowPriorityImplicits.charWrapper(c).toUpper)(scala.Predef.StringCanBuildFrom)
           |```
           |""".stripMargin
      )
    } yield ()
  }
}
