package tests.decorations

import tests.BaseLspSuite

class SyntheticHoverLspSuite extends BaseLspSuite("implicits") {

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
           |  "foo".map(c => c.toDouble)
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
        "  \"foo\".map(c @@=> c.toDouble)",
        """|**With synthetics added**:
           |```scala
           |scala.Predef.augmentString("foo").map[scala.Double](c => c.toDouble)
           |```
           |""".stripMargin
      )
    } yield ()
  }
}
