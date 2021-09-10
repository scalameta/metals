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
      _ = println(result)
    } yield
      if (result.contains("Error")) Assertions.fail("Command shouldn't failed")
      else ()
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
      _ = println(result)
    } yield
      if (result.contains("Error")) ()
      else Assertions.fail("Command should failed")
  }

  test("dont-open-tasty-file-scala2".only) {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "app": {
            |    "scalaVersion": "${V.scala213}"
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
      _ = println(result)
    } yield
      if (result.contains("Error")) ()
      else Assertions.fail("Command should failed")
  }
}
