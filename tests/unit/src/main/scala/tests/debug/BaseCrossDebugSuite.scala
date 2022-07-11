package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseDapSuite
import tests.BuildServerInitializer
import tests.BuildToolLayout

abstract class BaseCrossDebugSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout,
) extends BaseDapSuite(suiteName, initializer, buildToolLayout) {

  override def scalaVersion: String = V.scala3

  assertBreakpoints(
    "outer",
    main = Some("a.helloWorld"),
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
    main = Some("a.helloWorld"),
  )(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |@main 
                |def helloWorld(): Unit = {
                |  object Even {
                |>>  def unapply(s: String): Boolean = s.size % 2 == 0
                |  }
                |
                |  "even" match {
                |    case Even() => 
                |    case _      => 
                |  }
                |  System.exit(0)
                |}
                |
                |""".stripMargin
  )

  assertBreakpoints(
    "object-in-toplevel-method",
    main = Some("a.helloWorld"),
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

  assertBreakpoints(
    "optional-braces",
    main = Some("a.hello"),
  )(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |@main 
                |def hello(): Unit = 
                |  greet("Alice")
                |  System.exit(0)
                |
                |def greet(name: String) = 
                |>>val message = s"Hello, $name!"
                |>>println(message)
                |
                |""".stripMargin
  )

  assertBreakpoints(
    "optional-braces-main",
    main = Some("a.hello"),
  )(
    source = """|/a/src/main/scala/a/Main.scala
                |package a
                |
                |@main 
                |def hello(): Unit = 
                |>>greet("Alice")
                |>>System.exit(0)
                |
                |def greet(name: String) = 
                |  val message = s"Hello, $name!"
                |  println(message)
                |
                |""".stripMargin
  )

}
