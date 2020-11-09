package tests.decorations

import scala.meta.internal.metals.InitializationOptions

import tests.BaseLspSuite

class SyntheticDecorationsLspSuite extends BaseLspSuite("implicits") {

  override def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        inlineDecorationProvider = Some(true),
        decorationProvider = Some(true)
      )
    )

  test("basic") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |
           |/a/src/main/scala/Main.scala
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
           |    hello();    hello()
           |  }
           |  
           |  "foo".map(c => c.toUpper)
           |  "foo".map(c => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future{
           |    println("")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|import scala.concurrent.Future
           |case class Location(city: String)
           |object Main{
           |  def hello()(implicit name: String, from: Location) = {
           |    println(s"Hello $name from ${from.city}")
           |  }
           |  implicit val andy : String = "Andy"
           |
           |  def greeting() = {
           |    implicit val boston = Location("Boston")
           |    hello()(andy, boston)
           |    hello()(andy, boston);    hello()(andy, boston)
           |  }
           |  
           |  "foo".map[Char, String](c => c.toUpper)(StringCanBuildFrom)
           |  "foo".map[Int, IndexedSeq[Int]](c => c.toInt)(fallbackStringCanBuildFrom[Int])
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future{
           |    println("")
           |  }(ec)
           |}
           |""".stripMargin
      )
      // Implicit parameters
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "    hello()@@",
        """|**Synthetics**:
           |```scala
           |(Main.andy, boston)
           |```
           |""".stripMargin
      )
      // Inferred type parameters
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  \"foo\".map@@(c => c.toUpper)",
        """|**Expression type**:
           |```scala
           |String
           |```
           |**Symbol signature**:
           |```scala
           |def map[B, That](f: Char => B)(implicit bf: CanBuildFrom[String,B,That]): That
           |```
           |**Synthetics**:
           |```scala
           |[scala.Char, scala.Predef.String]
           |```
           |""".stripMargin
      )
      // Normal hover without synthetics
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  \"foo\".m@@ap(c => c.toUpper)",
        """|**Expression type**:
           |```scala
           |String
           |```
           |**Symbol signature**:
           |```scala
           |def map[B, That](f: Char => B)(implicit bf: CanBuildFrom[String,B,That]): That
           |```
           |""".stripMargin
      )
      // Implicit parameter from Predef
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  \"foo\".map(c => c.toInt)@@",
        """|**Synthetics**:
           |```scala
           |(scala.LowPriorityImplicits.fallbackStringCanBuildFrom[scala.Int])
           |```
           |""".stripMargin
      )
    } yield ()
  }

  test("single-option") {
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |
           |/a/src/main/scala/Main.scala
           |object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $$name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  "foo".map(c => c.toUpper)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-inferred-type": false
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()(andy)
           |  "foo".map(c => c.toUpper)(StringCanBuildFrom)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ = client.decorations.clear()
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  "foo".map[Char, String](c => c.toUpper)
           |}
           |""".stripMargin
      )
    } yield ()
  }
}
