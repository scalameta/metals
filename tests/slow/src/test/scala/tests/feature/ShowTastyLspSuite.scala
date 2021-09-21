package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import org.eclipse.{lsp4j => l}
import tests.Assertions
import tests.BaseLspSuite

class ShowTastyLspSuite extends BaseLspSuite("showTasty") {

  test("open-existing") {
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
            |object Main
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/a/b/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/a/b/Main.scala",
        new l.Position(1, 1)
      )
    } yield
      if (result.isLeft) Assertions.fail("Command shouldn't failed")
      else ()
  }

  test("handle-package-structure") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "app": {
            |    "scalaVersion": "${V.scala3}"
            |  }
            |}
            |/app/src/main/scala/Main.scala
            |package foo.bar.example
            |object Main
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/Main.scala",
        new l.Position(1, 1)
      )
    } yield
      if (result.isLeft) Assertions.fail("Command shouldn't failed")
      else ()
  }

  test("handle-multiple-classes") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "app": {
            |    "scalaVersion": "${V.scala3}"
            |  }
            |}
            |/app/src/main/scala/Main.scala
            |package foo.bar.example
            |class Foo
            |class Bar
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/Main.scala",
        new l.Position(1, 1)
      )
    } yield
      if (result.isLeft) Assertions.fail("Command shouldn't failed")
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
            |object Main
            |""".stripMargin
      )
      _ <- server.didOpen("app/src/main/scala/a/b/Main.scala")
      result <- server.showTasty(
        s"$workspace/app/src/main/scala/a/b/Main2.scala",
        new l.Position(1, 1)
      )
    } yield
      if (result.isLeft) ()
      else Assertions.fail("Command should failed")
  }

  test("dont-open-tasty-file-scala2") {
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
        s"$workspace/app/src/main/scala/a/b/Main.scala",
        new l.Position(1, 1)
      )
    } yield
      if (result.isLeft) ()
      else Assertions.fail("Command should failed")
  }
}
