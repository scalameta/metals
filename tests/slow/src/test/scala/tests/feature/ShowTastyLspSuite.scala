package tests.feature

import tests.BaseLspSuite
import tests.Assertions

import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.metals.MetalsEnrichments._

class ShowTastyLspSuite extends BaseLspSuite("showTasty") {

  test("open-existing-tasty-file") {
    cleanWorkspace()
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
      result <- server.assertShowTasty(
        s"$workspace/app/src/main/scala/a/b/Main.scala"
      )
    } yield {
      result.asScala match {
        case Some(params) if params.getArguments.size > 0 =>
          Assertions.assert(params.getArguments.asScala.nonEmpty)
        case _ =>
          Assertions.fail("Obtained empty result")
      }

    }
  }

  test("dont-open-nonexisting-tasty-file") {
    cleanWorkspace()
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
      result <- server.assertShowTasty(
        s"$workspace/app/src/main/scala/a/b/Main2.scala"
      )
    } yield {
      result.asScala match {
        case None => ()
        case _ => Assertions.fail("Obtained nonempty result")
      }
    }
  }
}
