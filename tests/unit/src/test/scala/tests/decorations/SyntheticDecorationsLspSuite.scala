package tests.decorations

import java.net.URLEncoder

import scala.meta.internal.metals.CommandHTMLFormat
import scala.meta.internal.metals.InitializationOptions

import tests.BaseLspSuite

class SyntheticDecorationsLspSuite extends BaseLspSuite("implicits") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        inlineDecorationProvider = Some(true),
        decorationProvider = Some(true),
        commandInHtmlFormat = Some(CommandHTMLFormat.VSCode),
      )
    )

  test("all-synthetics") {
    for {
      _ <- initialize(
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
           |  val ordered = "acb".sorted
           |  "foo".map(c => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future{
           |    println("")
           |  }
           |}
           |""".stripMargin
      )
      // minimal style for show-inferred-type : don't show types for match case
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": minimal
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff( // foo[t]() to foo()
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
           |  val ordered: String = augmentString("acb").sorted(Char)
           |  augmentString("foo").map(c: Char => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future{
           |    println("")
           |  }(ec)
           |}
           |""".stripMargin,
      )
      // full style for show-inferred-type
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
           |  val ordered: String = augmentString("acb").sorted[Char](Char)
           |  augmentString("foo").map[Int](c: Char => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future[Unit]{
           |    println("")
           |  }(ec)
           |}
           |""".stripMargin,
      )
      // Implicit parameters
      expectedParamsAndy = URLEncoder.encode("""["_empty_/Main.andy."]""")
      mainClassPath = workspace
        .resolve("a/src/main/scala/Main.scala")
        .toURI
        .toString()
      expectedParamsBoston = URLEncoder.encode(
        s"""[{"uri":"$mainClassPath","range":{"start":{"line":9,"character":17},"end":{"line":9,"character":23}},"otherWindow":false}]"""
      )
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "    hello()@@",
        s"""|**Synthetics**:
            |
            |([andy](command:metals.goto?$expectedParamsAndy), [boston](command:metals.metals-goto-location?$expectedParamsBoston))
            |""".stripMargin,
      )
      // Implicit conversions
      augmentStringParams = URLEncoder.encode(
        """["scala/Predef.augmentString()."]"""
      )
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  val ordered = @@\"acb\".sorted",
        s"""|**Synthetics**:
            |
            |[augmentString](command:metals.goto?$augmentStringParams)
            |""".stripMargin,
      )
      // Normal hover without synthetics
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  val or@@dered = \"acb\".sorted",
        """|```scala
           |val ordered: String
           |```
           |""".stripMargin,
      )
      orderingParams = URLEncoder.encode("""["scala/math/Ordering.Char."]""")
      charParams = URLEncoder.encode("""["scala/Char#"]""")
      // Implicit parameter from Math Ordering
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  val ordered = \"acb\".sorted@@",
        s"""|```scala
            |def sorted[B >: Char](implicit ord: Ordering[B]): String
            |```
            |Sorts the characters of this string according to an Ordering.
            |
            | The sort is stable. That is, elements that are equal (as determined by
            | `ord.compare`) appear in the same order in the sorted sequence as in the original.
            |
            |
            |**Notes**
            |- This method treats a string as a plain sequence of
            |Char code units and makes no attempt to keep
            |surrogate pairs or codepoint sequences together.
            |The user is responsible for making sure such cases
            |are handled correctly. Failing to do so may result in
            |an invalid Unicode string.
            |
            |**Parameters**
            |- `ord`: the ordering to be used to compare elements.
            |
            |**Returns:** a string consisting of the chars of this string
            |             sorted according to the ordering `ord`.
            |
            |**See**
            |- [scala.math.Ordering](scala.math.Ordering)
            |
            |**Synthetics**:
            |
            |([Char](command:metals.goto?$orderingParams))
            |[[Char](command:metals.goto?$charParams)]
            |""".stripMargin,
      )
    } yield ()
  }

  test("single-option") {
    for {
      _ <- initialize(
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
           |  ("1" + "2").map(c => c.toDouble)
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
           |  ("1" + "2").map(c => c.toDouble)
           |}
           |""".stripMargin,
      )
      _ = client.decorations.clear()
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": false,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String): Unit = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  ("1" + "2").map[Double](c: Char => c.toDouble)
           |}
           |""".stripMargin,
      )
      _ = client.decorations.clear()
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": false
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  (augmentString("1" + "2")).map(c => c.toDouble)
           |}
           |""".stripMargin,
      )
      // no decorations should show up with everything set to false
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": false,
          |  "show-inferred-type": false
          |}
          |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  ("1" + "2").map(c => c.toDouble)
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("augment-string") {
    for {
      _ <- initialize(
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
           |  augmentString(augmentString((augmentString("1" + "2"))
           |    .stripSuffix("."))
           |    .stripSuffix("#"))
           |    .stripPrefix("_empty_.")
           |}
           |""".stripMargin,
      )
      augmentStringParams = URLEncoder.encode(
        """["scala/Predef.augmentString()."]"""
      )
      _ <- server.assertHoverAtLine(
        "a/src/main/scala/Main.scala",
        "  (@@\"1\" + \"2\")",
        s"""|**Synthetics**:
            |
            |[augmentString](command:metals.goto?$augmentStringParams)
            |""".stripMargin,
      )
    } yield ()
  }

  test("readonly-files") {
    for {
      _ <- initialize(
        s"""|/metals.json
            |{
            |  "a": {}
            |}
            |
            |/standalone/Main.scala
            |object Main{
            |  val value = "asd.".stripSuffix(".")
            |}
            |""".stripMargin
      )
      _ <- server.didChangeConfiguration(
        """|{
           |  "show-implicit-conversions-and-classes": true,
           |  "show-inferred-type": true
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("standalone/Main.scala")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  val value: String = augmentString("asd.").stripSuffix(".")
           |}
           |""".stripMargin,
      )
      augmentStringParams = URLEncoder.encode(
        """["scala/Predef.augmentString()."]"""
      )
      _ <- server.assertHoverAtLine(
        "standalone/Main.scala",
        "  val value = @@\"asd.\".stripSuffix(\".\")",
        s"""|**Synthetics**:
            |
            |[augmentString](command:metals.goto?$augmentStringParams)
            |""".stripMargin,
      )
      _ <- server.didChange("standalone/Main.scala") { _ =>
        s"""|object Main{
            |  "asd.".stripSuffix(".")
            |  "asd.".stripSuffix(".")
            |}
            |""".stripMargin
      }
      _ <- server.didSave("standalone/Main.scala")(identity)
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  augmentString("asd.").stripSuffix(".")
           |  augmentString("asd.").stripSuffix(".")
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("inferred-type-various") {
    for {
      _ <- initialize(
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
           |  val tupleExplicit = Tuple2("1", "2")
           |  val tupleExplicitApply = Tuple2.apply("1", "2")
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
           |  def convert()(name: String => String = _ => "") = {
           |    println(s"Hello $$name!")
           |  }
           |  val tpl1 = (123, 1)
           |  val tpl2 = (123, 1, 3)
           |  val func0 = () => 2
           |  val func1 = (a : Int) => a + 2
           |  val func2 = (a : Int, b: Int) => a + b
           |  val complex = tail.zip(1 to 12)
           |  for{
           |    i <- complex
           |    c = func1
           |  } i match {
           |    case (b, c: Int) =>
           |    case a =>
           |  }
           |  convert(){str => str.stripMargin}
           |  convert(){str : String => str.stripMargin}
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
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|object Main{
           |  val head: Double :: tail: List[Double] = List[Double](0.1, 0.2, 0.3)
           |  val List[Int](l1: Int, l2: Int) = List[Int](12, 13)
           |  println("Hello!")
           |  val abc: Int = 123
           |  val tupleBound @ (one: String, two: String) = ("1", "2")
           |  val tupleExplicit: (String, String) = Tuple2[String, String]("1", "2")
           |  val tupleExplicitApply: (String, String) = Tuple2.apply[String, String]("1", "2")
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
           |  def convert()(name: String => String = _ => ""): Unit = {
           |    println(s"Hello $name!")
           |  }
           |  val tpl1: (Int, Int) = (123, 1)
           |  val tpl2: (Int, Int, Int) = (123, 1, 3)
           |  val func0: () => Int = () => 2
           |  val func1: Int => Int = (a : Int) => a + 2
           |  val func2: (Int, Int) => Int = (a : Int, b: Int) => a + b
           |  val complex: List[(Double, Int)] = tail.zip[Int](1 to 12)
           |  for{
           |    i: (Double, Int) <- complex
           |    c: Int => Int = func1
           |  } i match {
           |    case (b: Double, c: Int) =>
           |    case a: (Double, Int) =>
           |  }
           |  convert(){str: String => str.stripMargin}
           |  convert(){str : String => str.stripMargin}
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("inferred-type-complex") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |
           |/a/src/main/scala/Main.scala
           |import scala.concurrent.ExecutionContextExecutorService
           |
           |trait A
           |trait B
           |
           |class Main(implicit ec: ExecutionContextExecutorService){
           |  // structural types
           |  val anon1 = new A {}
           |  val anon2 = new A { def a = 123 }
           |  val anon3 = new A with B {}
           |  // existential type
           |  val job = ec.submit(new Runnable {
           |     override def run(): Unit = {}
           |  })
           |  val runnable = new Runnable {
           |    override def run(): Unit = {}
           |  }
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
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|import scala.concurrent.ExecutionContextExecutorService
           |
           |trait A
           |trait B
           |
           |class Main(implicit ec: ExecutionContextExecutorService){
           |  // structural types
           |  val anon1: A = new A {}
           |  val anon2: A{def a: Int} = new A { def a: Int = 123 }
           |  val anon3: A with B = new A with B {}
           |  // existential type
           |  val job: Future[_ <: Object] = ec.submit(new Runnable {
           |     override def run(): Unit = {}
           |  })
           |  val runnable: Runnable = new Runnable {
           |    override def run(): Unit = {}
           |  }
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("inferred-type-changes") {
    for {
      _ <- initialize(
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
        """|object Main{
           |  val abc: Int = 123
           |}
           |""".stripMargin,
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
           |""".stripMargin,
      )
      // introduce a parsing error
      _ <- server.didChange("a/src/main/scala/Main.scala") { text =>
        "}\n" + text
      }
    } yield ()
  }

  test("inferred-type-hkt") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies": [
           |        "org.typelevel::cats-effect:2.4.0"
           |    ]
           |  }
           |}
           |
           |/a/src/main/scala/Main.scala
           |
           |import cats.Parallel
           |import cats.effect.ConcurrentEffect
           |import cats.effect.ContextShift
           |import cats.effect.IOApp
           |import cats.effect.IO
           |import cats.effect.ExitCode
           |import cats.effect.Resource
           |import cats.effect.Timer
           |
           |object Main2 extends IOApp {
           |
           |  trait Logger[T[_]]
           |
           |  def mkLogger[F[_]: ConcurrentEffect: Timer: ContextShift]: Resource[F, Logger[F]] = ???
           |
           |  // The actual tested method:
           |  def serve[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel]() =
           |    for {
           |      logger <- mkLogger[F]
           |    } yield ()
           |
           |  def run(args: List[String]): IO[ExitCode] = ???
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
      _ <- server.didOpen("a/src/main/scala/Main.scala")
      _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|import cats.Parallel
           |import cats.effect.ConcurrentEffect
           |import cats.effect.ContextShift
           |import cats.effect.IOApp
           |import cats.effect.IO
           |import cats.effect.ExitCode
           |import cats.effect.Resource
           |import cats.effect.Timer
           |
           |object Main2 extends IOApp {
           |
           |  trait Logger[T[_]]
           |
           |  def mkLogger[F[_]: ConcurrentEffect: Timer: ContextShift]: Resource[F, Logger[F]] = ???
           |
           |  // The actual tested method:
           |  def serve[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](): Resource[F,Unit] =
           |    for {
           |      logger: Logger[F] <- mkLogger[F]
           |    } yield ()
           |
           |  def run(args: List[String]): IO[ExitCode] = ???
           |}
           |""".stripMargin,
      )
    } yield ()
  }

  test("worksheet") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/Main.worksheet.sc
           |def method(implicit str: String) = str + str
           |implicit val name: String = "Susan".stripMargin
           |val greeting = s"Hello $$name"
           |method
           |""".stripMargin
      )
      _ <- server.didOpen("a/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|def method(implicit str: String) = str + str
           |implicit val name: String = "Susan".stripMargin // : String = "Susan"
           |val greeting = s"Hello $name" // : String = "Hello Susan"
           |method // : String = "SusanSusan"
           |""".stripMargin,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": true,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|def method(implicit str: String): String = str + str
           |implicit val name: String = augmentString("Susan").stripMargin // : String = "Susan"
           |val greeting: String = s"Hello $name" // : String = "Hello Susan"
           |method(name) // : String = "SusanSusan"
           |""".stripMargin,
      )
    } yield ()
  }

  // https://github.com/scalameta/metals/pull/3948
  test("for-yield") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/Main.scala
           |class Evidence
           |
           |final case class DataType[T](number: Int, other: T) {
           |  def next(implicit evidence: Evidence): Int = number + 1
           |}
           |
           |object Example extends App {
           |
           |  implicit val evidence: Evidence = ???
           |
           |  def opts(i: Int)(implicit ev: Evidence) = Option(i)
           |  for {
           |    number <- Option(5)
           |    num2 <- opts(2)
           |    _ = "abc ".stripMargin
           |  } yield DataType(number, "").next
           |
           |  Option(5).map(DataType(_, "").next)
           |
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
      _ <- server.didOpen("a/Main.scala")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|class Evidence
           |
           |final case class DataType[T](number: Int, other: T) {
           |  def next(implicit evidence: Evidence): Int = number + 1
           |}
           |
           |object Example extends App {
           |
           |  implicit val evidence: Evidence = ???
           |
           |  def opts(i: Int)(implicit ev: Evidence): Option[Int] = Option[Int](i)
           |  for {
           |    number: Int <- Option[Int](5)
           |    num2: Int <- opts(2)(evidence)
           |    _ = augmentString("abc ").stripMargin
           |  } yield DataType[String](number, "").next(evidence)
           |
           |  Option[Int](5).map[Int](DataType[String](_, "").next(evidence))
           |
           |}
           |""".stripMargin,
      )
      expectedParams = URLEncoder.encode(
        """["_empty_/Example.evidence."]"""
      )
      _ <- server.assertHoverAtLine(
        "a/Main.scala",
        "    num2 <- opts(2)@@",
        s"""|**Synthetics**:
            |
            |([evidence](command:metals.goto?$expectedParams))""".stripMargin,
      )
    } yield ()
  }

  test("type-aliases") {
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/Main.scala
           |object O {
           | type Foo3[T, R] = (T, R, "")
           | def hello: Option[(Int, String)] = {
           |  type Foo = (Int, String)
           |  type Foo2[T] = (T, String)
           |  def foo: Option[Foo] = ???
           |  def foo2: Option[Foo2[Int]] = Option((1, ""))
           |  def foo3: Option[Foo3[Int, String]] = Option((1, "", ""))
           |  for {
           |    a <- foo
           |    b <- foo2
           |    c <- foo3
           |  } yield a
           | }
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
        """|object O {
           | type Foo3[T, R] = (T, R, "")
           | def hello: Option[(Int, String)] = {
           |  type Foo = (Int, String)
           |  type Foo2[T] = (T, String)
           |  def foo: Option[Foo] = ???
           |  def foo2: Option[Foo2[Int]] = Option[(Int, String)]((1, ""))
           |  def foo3: Option[Foo3[Int, String]] = Option[(Int, String, "")]((1, "", ""))
           |  for {
           |    a: Foo <- foo
           |    b: Foo2[Int] <- foo2
           |    c: Foo3[Int,String] <- foo3
           |  } yield a
           | }
           |}
           |""".stripMargin,
      )
    } yield ()
  }
  // Todo: Unignore test
  // test("value-of") {
  //   for {
  //     _ <- initialize(
  //       """|/metals.json
  //          |{
  //          |  "a": {}
  //          |}
  //          |/a/src/main/scala/Main.scala
  //          |object O {
  //          |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
  //          |  val m = foo[500]
  //          |}
  //          |""".stripMargin
  //     )
  //     _ <- server.didChangeConfiguration(
  //       """{
  //         |  "show-implicit-arguments": true
  //         |}
  //         |""".stripMargin
  //     )
  //     _ <- server.didOpen("a/src/main/scala/Main.scala")
  //     _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
  //     _ = assertNoDiagnostics()
  //     _ = assertNoDiff(
  //       client.workspaceDecorations,
  //       """|object O {
  //          |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
  //          |  val m = foo[500](new ValueOf(...))
  //          |}
  //          |""".stripMargin,
  //     )
  //   } yield ()
  // }
}
