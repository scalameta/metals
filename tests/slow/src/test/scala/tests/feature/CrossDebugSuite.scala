package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseDapSuite

class CrossDebugSuite extends BaseDapSuite("cross-debug") {

  override def scalaVersion: String = V.scala3

  assertBreakpoints(
    "outer",
    main = Some("a.helloWorld")
  )(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |@main 
                |def helloWorld(): Unit = {
                |>>println("Hello world")
                |  System.exit(0)
                |}
                |
                |""".stripMargin
  )

  assertBreakpoints(
    "unapply",
    main = Some("a.helloWorld")
  )(
    source =
      """|/a/src/main/scala/a/Main.scala
         |package a
         |
         |@main 
         |def helloWorld(): Unit = {
         |  object Even {
         |>>  def unapply(s: String): Boolean = s.size % 2 == 0
         |  }
         |
         |  "even" match {
         |    case s @ Even() => println(s"$s has an even number of characters")
         |    case s          => println(s"$s has an odd number of characters")
         |  }
         |  System.exit(0)
         |}
         |
         |""".stripMargin
  )

  assertBreakpoints(
    "outer-object",
    main = Some("a.helloWorld")
  )(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |@main 
                |def helloWorld(): Unit = {
                |  object Hello{
                |    def run() = {
                |>>    println("Hello world")
                |    }
                |  }
                |  Hello.run()
                |  System.exit(0)
                |}
                |
                |
                |""".stripMargin
  )

}
