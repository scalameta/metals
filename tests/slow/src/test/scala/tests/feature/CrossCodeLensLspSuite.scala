package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCodeLensLspSuite

class CrossCodeLensLspSuite extends BaseCodeLensLspSuite("cross-code-lens") {

  check("main-method-scala3", scalaVersion = Some(V.scala3))(
    """|package foo
       |
       |<<run>><<debug>>
       |@main def mainMethod(): Unit = {
       |  println("Hello world!")
       |}
       |""".stripMargin
  )

  test("no-stale-run-debug") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {
            |    "scalaVersion": "${V.scala3}"
            |  }
            |}
            |/a/src/main/scala/a/A.scala
            |package a
            |@main
            |def mainMethod(): Unit = {
            |  println("Hello world!")
            |}
            |
            |object Main {
            |  def main(args: Array[String]): Unit = 
            |    println("Hello again!")
            |}
            |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- assertCodeLenses(
        "a/src/main/scala/a/A.scala",
        """|package a
           |<<run>><<debug>>
           |@main
           |def mainMethod(): Unit = {
           |  println("Hello world!")
           |}
           |
           |<<run>><<debug>>
           |object Main {
           |  def main(args: Array[String]): Unit = 
           |    println("Hello again!")
           |}
           |""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        s"""|package a
            |
            |def mainMethod(): Unit = {
            |  println("Hello world!")
            |}
            |
            |object Main {
            |  def main(args: Array[String]): Unit = 
            |    println("Hello again!")
            |}
            |""".stripMargin
      }
      _ <- assertCodeLenses(
        "a/src/main/scala/a/A.scala",
        """|package a
           |
           |def mainMethod(): Unit = {
           |  println("Hello world!")
           |}
           |
           |<<run>><<debug>>
           |object Main {
           |  def main(args: Array[String]): Unit = 
           |    println("Hello again!")
           |}
           |""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/a/A.scala") { _ =>
        s"""|package a
            |
            |@main
            |def mainMethod(): Unit = {
            |  println("Hello world!")
            |}
            |
            |//object Main {
            |//  def main(args: Array[String]): Unit = 
            |//    println("Hello again!")
            |//}
            |""".stripMargin
      }
      _ <- assertCodeLenses(
        "a/src/main/scala/a/A.scala",
        """|package a
           |
           |<<run>><<debug>>
           |@main
           |def mainMethod(): Unit = {
           |  println("Hello world!")
           |}
           |
           |//object Main {
           |//  def main(args: Array[String]): Unit = 
           |//    println("Hello again!")
           |//}
           |""".stripMargin
      )
    } yield ()
  }

}
