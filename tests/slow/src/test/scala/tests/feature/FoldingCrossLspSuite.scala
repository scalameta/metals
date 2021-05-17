package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class FoldingCrossLspSuite extends BaseLspSuite("foldingRange-cross") {

  test("base-213") {
    for {
      _ <- server.initialize(
        s"""|
            |/metals.json
            |{
            |  "a": { "scalaVersion" : "${V.scala213}" }
            |}
            |/a/src/main/scala/a/Main.scala
            |object Main {
            |  def foo = {
            |    ???
            |    ???
            |    ???
            |  }
            |
            |  val justAPadding = ???
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.assertFolded(
        "a/src/main/scala/a/Main.scala",
        """object Main >>region>>{
          |  def foo = >>region>>{
          |    ???
          |    ???
          |    ???
          |  }<<region<<
          |
          |  val justAPadding = ???
          |}<<region<<""".stripMargin
      )
    } yield ()
  }

  test("source-3") {
    for {
      _ <- server.initialize(
        s"""|
            |/metals.json
            |{
            |  "a": { "scalaVersion" : "${V.scala213}", "scalacOptions" : ["-Xsource:3"] }
            |}
            |/a/src/main/scala/a/Main.scala
            |package b
            |import scala.concurrent.Future as F
            |object a {
            |  def func(args: String*) = {
            |    println("")
            |    println("")
            |    println("")
            |    println("")
            |  }
            |  val args = List.empty[String]
            |  func(args*) 
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = assertNoDiagnostics()
      _ <- server.assertFolded(
        "a/src/main/scala/a/Main.scala",
        """|package b
           |import scala.concurrent.Future as F
           |object a >>region>>{
           |  def func(args: String*) = >>region>>{
           |    println("")
           |    println("")
           |    println("")
           |    println("")
           |  }<<region<<
           |  val args = List.empty[String]
           |  func(args*) 
           |}<<region<<
           |""".stripMargin
      )
    } yield ()
  }
}
