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
}
