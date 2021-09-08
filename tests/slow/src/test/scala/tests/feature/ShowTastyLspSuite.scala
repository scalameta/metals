package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.Assertions
import tests.BaseLspSuite

class ShowTastyLspSuite extends BaseLspSuite("showTasty") {

  test("open-existing-tasty-file") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "app": {
            |    "scalaVersion": "${V.scala3}"
            |  }
            |}
            |/app/src/main/scala/a/b/Main.scala
            |package a.b
            |object Main {
            |  println(5)
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/a/b/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/a/b/Main.scala"
      )
    } yield {
      result match {
        case Some(content) if content.nonEmpty => ()
        case _ =>
          Assertions.fail("Obtained empty result")
      }

    }
  }

  test("dont-open-nonexisting-tasty-file") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "app": {
            |    "scalaVersion": "${V.scala3}"
            |  }
            |}
            |/app/src/main/scala/a/b/Main.scala
            |package a.b
            |object Main {
            |  println(5)
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/a/b/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/a/b/Main2.scala"
      )
    } yield {
      result match {
        case None => ()
        case _ => Assertions.fail("Obtained nonempty result")
      }
    }
  }
}
