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

  test("all-synthetics") {
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
          |  "show-implicit-conversions-and-classes": true,
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
           |  def hello()(implicit name: String, from: Location): Unit = {
           |    println(s"Hello $name from ${from.city}")
           |  }
           |  implicit val andy : String = "Andy"
           |
           |  def greeting(): Unit = {
           |    implicit val boston: Location = Location("Boston")
           |    hello()(andy, boston)
           |    hello()(andy, boston);    hello()(andy, boston)
           |  }
           |  
           |  augmentString("foo").map[Char, String](c => charWrapper(c).toUpper)(StringCanBuildFrom)
           |  augmentString("foo").map[Int, IndexedSeq[Int]](c => c.toInt)(fallbackStringCanBuildFrom[Int])
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
      // Implicit converions
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  @@\"foo\".map(c => c.toUpper)",
        """|**Synthetics**:
           |```scala
           |scala.Predef.augmentString
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
           |
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
           |  ("1" + "2").map(c => c.toUpper)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions-and-classes": false,
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
           |  ("1" + "2").map(c => c.toUpper)(StringCanBuildFrom)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": false,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ = client.decorations.clear()
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String): Unit = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  ("1" + "2").map[Char, String](c => c.toUpper)
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": false
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
           |  (augmentString("1" + "2")).map(c => charWrapper(c).toUpper)
           |}
           |""".stripMargin
      )
    } yield ()
  }

  test("augment-string") {
    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |
            |/a/src/main/scala/Main.scala
            |object Main{
            |  ("1" + "2")
            |    .stripSuffix(".")
            |    .stripSuffix("#")
            |    .stripPrefix("_empty_.")
            |}
            |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """|{
           |  "show-implicit-conversions-and-classes": true
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  (augmentString(augmentString(augmentString("1" + "2"))
           |    .stripSuffix("."))
           |    .stripSuffix("#"))
           |    .stripPrefix("_empty_.")
           |}
           |""".stripMargin
      )
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  (@@\"1\" + \"2\")",
        """|**Synthetics**:
           |```scala
           |scala.Predef.augmentString
           |```
           |""".stripMargin
      )
    } yield ()
  }

  test("readonly-files") {
    for {
      _ <- server.initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |
            |/standalone/Main.scala
            |object Main{
            |  "asd.".stripSuffix(".")
            |}
            |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """|{
           |  "show-implicit-conversions-and-classes": true
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("standalone/Main.scala")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  augmentString("asd.").stripSuffix(".")
           |}
           |""".stripMargin
      )
      _ <- server.assertHoverAtLine(
        "standalone/Main.scala",
        "  @@\"asd.\".stripSuffix(\".\")",
        """|**Synthetics**:
           |```scala
           |scala.Predef.augmentString
           |```
           |""".stripMargin
      )
      _ <- server.didSave("standalone/Main.scala") { _ =>
        s"""|object Main{
            |  "asd.".stripSuffix(".")
            |  "asd.".stripSuffix(".")
            |}
            |""".stripMargin
      }
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  augmentString("asd.").stripSuffix(".")
           |  augmentString("asd.").stripSuffix(".")
           |}
           |""".stripMargin
      )
    } yield ()
  }

  test("inferred-type-various") {
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
           |  val head :: tail = List(0.1, 0.2, 0.3)
           |  val List(l1, l2) = List(12, 13)
           |  println("Hello!")
           |  val abc = 123
           |  val tupleBound @ (one, two) = ("1", "2")
           |  var variable = 123
           |  val bcd: Int = 2
           |  val (hello, bye) = ("hello", "bye")
           |  def method() = {
           |    val local = 1.0
           |  }
           |  def methodNoParen = {
           |    val (local, _) = ("", 1.0)
           |  }
           |  def hello()(name: String) = {
           |    println(s"Hello $$name!")
           |  }
           |  def hello2()(name: String = "") = {
           |    println(s"Hello $$name!")
           |  }
           |  val tpl1 = (123, 1)
           |  val tpl2 = (123, 1, 3)
           |  val func0 = () => 2
           |  val func1 = (a : Int) => a + 2
           |  val func2 = (a : Int, b: Int) => a + b
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions": true,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  val head: Double :: tail: List[Double] = List(0.1, 0.2, 0.3)
           |  val List(l1: Int, l2: Int) = List(12, 13)
           |  println("Hello!")
           |  val abc: Int = 123
           |  val tupleBound: (String, String) @ (one: String, two: String) = ("1", "2")
           |  var variable: Int = 123
           |  val bcd: Int = 2
           |  val (hello: String, bye: String) = ("hello", "bye")
           |  def method(): Unit = {
           |    val local: Double = 1.0
           |  }
           |  def methodNoParen: Unit = {
           |    val (local: String, _) = ("", 1.0)
           |  }
           |  def hello()(name: String): Unit = {
           |    println(s"Hello $name!")
           |  }
           |  def hello2()(name: String = ""): Unit = {
           |    println(s"Hello $name!")
           |  }
           |  val tpl1: (Int, Int) = (123, 1)
           |  val tpl2: (Int, Int, Int) = (123, 1, 3)
           |  val func0: () => Int = () => 2
           |  val func1: (Int) => Int = (a : Int) => a + 2
           |  val func2: (Int, Int) => Int = (a : Int, b: Int) => a + b
           |}
           |""".stripMargin
      )
    } yield ()
  }

  test("inferred-type-changes") {
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
           |  val abc = 123
           |}
           |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions": true,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  val abc: Int = 123
           |}
           |""".stripMargin
      )
      // introduce a change
      _ <- server.didChange("a/src/main/scala/Main.scala") { text =>
        "\n" + text
      }
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|
           |object Main{
           |  val abc: Int = 123
           |}
           |""".stripMargin
      )
      // introduce a parsing error
      _ <- server.didChange("a/src/main/scala/Main.scala") { text =>
        "}\n" + text
      }
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|}
           |
           |object Main{
           |  val abc: Int = 123
           |}
           |""".stripMargin
      )
    } yield ()
  }
}
