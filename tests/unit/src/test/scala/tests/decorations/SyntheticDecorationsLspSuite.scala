package tests.decorations

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

  // TODO: Fix hover for local symbols
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
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|import scala.concurrent.Future
           |case class Location(city: String)
           |object Main{
           |  def hello()(implicit name: String, from: Location)/*: Unit<<scala/Unit#>>*/ = {
           |    println(s"Hello $name from ${from.city}")
           |  }
           |  implicit val andy : String = "Andy"
           |
           |  def greeting()/*: Unit<<scala/Unit#>>*/ = {
           |    implicit val boston/*: Location<<_empty_/Location#>>*/ = Location("Boston")
           |    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/
           |    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/;    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/
           |  }
           |  
           |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*(Char<<scala/math/Ordering.Char.>>)*/
           |  /*augmentString<<scala/Predef.augmentString().>>(*/"foo"/*)*/.map(c/*: Char<<scala/Char#>>*/ => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future{
           |    println("")
           |  }/*(ec<<_empty_/Main.ec.>>)*/
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
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|import scala.concurrent.Future
           |case class Location(city: String)
           |object Main{
           |  def hello()(implicit name: String, from: Location)/*: Unit<<scala/Unit#>>*/ = {
           |    println(s"Hello $name from ${from.city}")
           |  }
           |  implicit val andy : String = "Andy"
           |
           |  def greeting()/*: Unit<<scala/Unit#>>*/ = {
           |    implicit val boston/*: Location<<_empty_/Location#>>*/ = Location("Boston")
           |    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/
           |    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/;    hello()/*(andy<<_empty_/Main.andy.>>, boston<<local0>>)*/
           |  }
           |  
           |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*[Char<<scala/Char#>>]*//*(Char<<scala/math/Ordering.Char.>>)*/
           |  /*augmentString<<scala/Predef.augmentString().>>(*/"foo"/*)*/.map/*[Int<<scala/Int#>>]*/(c/*: Char<<scala/Char#>>*/ => c.toInt)
           |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
           |  Future/*[Unit<<scala/Unit#>>]*/{
           |    println("")
           |  }/*(ec<<_empty_/Main.ec.>>)*/
           |}
           |""".stripMargin,
      )
      // Implicit parameters
      _ <- server.assertInlayHintResolve(
        "a/src/main/scala/Main.scala",
        s"""|import scala.concurrent.Future
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
            |    hello();    hello()@@
            |  }
            |}""".stripMargin,
        List("""|```scala
                |implicit val andy: String
                |```
                |""".stripMargin),
      )
      // Type parameter
      _ <- server.assertInlayHintResolve(
        "a/src/main/scala/Main.scala",
        s"""|import scala.concurrent.Future
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
            |  val ordered = "acb".sorted@@
            |}""".stripMargin,
        List(
          """|```scala
             |final abstract class Char: Char
             |```
             |`Char`, a 16-bit unsigned integer (equivalent to Java's `char` primitive type) is a
             | subtype of [scala.AnyVal](scala.AnyVal). Instances of `Char` are not
             | represented by an object in the underlying runtime system.
             |
             | There is an implicit conversion from [scala.Char](scala.Char) => [scala.runtime.RichChar](scala.runtime.RichChar)
             | which provides useful non-primitive operations.
             |""".stripMargin
        ),
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()/*(andy<<_empty_/Main.andy.>>)*/
           |  ("1" + "2").map(c => c.toDouble)
           |}
           |""".stripMargin,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": false,
          |  "show-inferred-type": true
          |}
          |""".stripMargin
      )
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  def hello()(implicit name: String)/*: Unit<<scala/Unit#>>*/ = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  ("1" + "2").map/*[Double<<scala/Double#>>]*/(c/*: Char<<scala/Char#>>*/ => c.toDouble)
           |}
           |""".stripMargin,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "show-implicit-arguments": false,
          |  "show-implicit-conversions-and-classes": true,
          |  "show-inferred-type": false
          |}
          |""".stripMargin
      )
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  def hello()(implicit name: String) = {
           |    println(s"Hello $name!")
           |  }
           |  implicit val andy : String = "Andy"
           |  hello()
           |  (/*augmentString<<scala/Predef.augmentString().>>(*/"1" + "2"/*)*/).map(c => c.toDouble)
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
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  /*augmentString<<scala/Predef.augmentString().>>(*//*augmentString<<scala/Predef.augmentString().>>(*/(/*augmentString<<scala/Predef.augmentString().>>(*/"1" + "2"/*)*/)
           |    .stripSuffix(".")/*)*/
           |    .stripSuffix("#")/*)*/
           |    .stripPrefix("_empty_.")
           |}
           |""".stripMargin,
      )
      _ <- server.assertInlayHintResolve(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  @@("1" + "2")
           |    .stripSuffix(".")
           |    .stripSuffix("#")
           |    .stripPrefix("_empty_.")
           |}
           |""".stripMargin,
        List(
          """|```scala
             |implicit def augmentString(x: String): StringOps
             |```
             |""".stripMargin
        ),
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  val head/*: Double<<scala/Double#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Double<<scala/Double#>>]*/ = List/*[Double<<scala/Double#>>, Double<<scala/Double#>>]*/(0.1, 0.2, 0.3)
           |  val List/*[Int<<scala/Int#>>]*/(l1/*: Int<<scala/Int#>>*/, l2/*: Int<<scala/Int#>>*/) = List/*[Int<<scala/Int#>>, Int<<scala/Int#>>]*/(12, 13)
           |  println("Hello!")
           |  val abc/*: Int<<scala/Int#>>*/ = 123
           |  val tupleBound @ (one/*: String<<java/lang/String#>>*/, two/*: String<<java/lang/String#>>*/) = ("1", "2")
           |  val tupleExplicit/*: (String<<java/lang/String#>>, String<<java/lang/String#>>)*/ = Tuple2("1", "2")
           |  val tupleExplicitApply/*: (String<<java/lang/String#>>, String<<java/lang/String#>>)*/ = Tuple2.apply("1", "2")
           |  var variable/*: Int<<scala/Int#>>*/ = 123
           |  val bcd: Int = 2
           |  val (hello/*: String<<java/lang/String#>>*/, bye/*: String<<java/lang/String#>>*/) = ("hello", "bye")
           |  def method()/*: Unit<<scala/Unit#>>*/ = {
           |    val local/*: Double<<scala/Double#>>*/ = 1.0
           |  }
           |  def methodNoParen/*: Unit<<scala/Unit#>>*/ = {
           |    val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
           |  }
           |  def hello()(name: String)/*: Unit<<scala/Unit#>>*/ = {
           |    println(s"Hello $name!")
           |  }
           |  def convert()(name: String => String = _ => "")/*: Unit<<scala/Unit#>>*/ = {
           |    println(s"Hello $name!")
           |  }
           |  val tpl1/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (123, 1)
           |  val tpl2/*: (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (123, 1, 3)
           |  val func0/*: () => Int<<scala/Int#>>*/ = () => 2
           |  val func1/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = (a : Int) => a + 2
           |  val func2/*: (Int<<scala/Int#>>, Int<<scala/Int#>>) => Int<<scala/Int#>>*/ = (a : Int, b: Int) => a + b
           |  val complex/*: List<<scala/collection/immutable/List#>>[(Double<<scala/Double#>>, Int<<scala/Int#>>)]*/ = tail.zip/*[Int<<scala/Int#>>]*/(1 to 12)
           |  for{
           |    i/*: (Double<<scala/Double#>>, Int<<scala/Int#>>)*/ <- complex/*[((Double<<scala/Double#>>, Int<<scala/Int#>>), Int<<scala/Int#>> => Int<<scala/Int#>>)]*/
           |    c/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = func1/*[Unit<<scala/Unit#>>]*/
           |  } i match {
           |    case (b/*: Double<<scala/Double#>>*/, c: Int) =>
           |    case a/*: (Double<<scala/Double#>>, Int<<scala/Int#>>)*/ =>
           |  }
           |  convert(){str/*: String<<scala/Predef.String#>>*/ => str.stripMargin}
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|import scala.concurrent.ExecutionContextExecutorService
           |
           |trait A
           |trait B
           |
           |class Main(implicit ec: ExecutionContextExecutorService){
           |  // structural types
           |  val anon1/*: A<<_empty_/A#>>*/ = new A {}
           |  val anon2/*: A<<_empty_/A#>>{def a: Int<<scala/Int#>>}*/ = new A { def a/*: Int<<scala/Int#>>*/ = 123 }
           |  val anon3/*: A<<_empty_/A#>> with B<<_empty_/B#>>*/ = new A with B {}
           |  // existential type
           |  val job/*: Future<<java/util/concurrent/Future#>>[_ <: Object<<java/lang/Object#>>]*/ = ec.submit(new Runnable {
           |     override def run(): Unit = {}
           |  })
           |  val runnable/*: Runnable<<java/lang/Runnable#>>*/ = new Runnable {
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  val abc/*: Int<<scala/Int#>>*/ = 123
           |}
           |""".stripMargin,
      )
      // introduce a change
      _ <- server.didSave("a/src/main/scala/Main.scala") { text =>
        "\n" + text
      }
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|object Main{
           |  val abc/*: Int<<scala/Int#>>*/ = 123
           |}
           |""".stripMargin,
      )
      // introduce a parsing error
      // should show empty decorations
      _ <- server.didSave("a/src/main/scala/Main.scala") { text =>
        "}\n" + text
      }
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
        """|}
           |
           |object Main{
           |  val abc = 123
           |}
           |""".stripMargin,
      )
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
      _ = assertNoDiagnostics()
      _ <- server.assertInlayHints(
        "a/src/main/scala/Main.scala",
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
           |  def serve[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel]()/*: Resource<<cats/effect/Resource#>>[F<<_empty_/Main2.serve().[F]>>,Unit<<scala/Unit#>>]*/ =
           |    for {
           |      logger/*: Logger<<_empty_/Main2.Logger#>>[F<<_empty_/Main2.serve().[F]>>]*/ <- mkLogger[F]/*[F<<_empty_/Main2.serve().[F]>>[x<<cats/effect/ResourceLike#map().[G][x]>>], Unit<<scala/Unit#>>]*/
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
           |implicit val name = "Susan".stripMargin
           |val greeting = s"Hello $$name"
           |method
           |""".stripMargin
      )
      _ <- server.didOpen("a/Main.worksheet.sc")
      _ = assertNoDiagnostics()
      _ = assertNoDiff(
        client.workspaceDecorations,
        """|def method(implicit str: String) = str + str
           |implicit val name = "Susan".stripMargin // : String = "Susan"
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
        """|def method(implicit str: String) = str + str
           |implicit val name = "Susan".stripMargin // : String = "Susan"
           |val greeting = s"Hello $name" // : String = "Hello Susan"
           |method // : String = "SusanSusan"
           |""".stripMargin,
      )
      _ <- server.assertInlayHints(
        "a/Main.worksheet.sc",
        """|def method(implicit str: String)/*: String<<java/lang/String#>>*/ = str + str
           |implicit val name/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"Susan"/*)*/.stripMargin
           |val greeting/*: String<<java/lang/String#>>*/ = s"Hello $name"
           |method/*(name<<local0>>)*/
           |""".stripMargin,
      )
    } yield ()
  }

  // TODO: Fix test after merging https://github.com/scalameta/metals/pull/5552
  // test("for-yield") {
  //   for {
  //     _ <- initialize(
  //       s"""
  //          |/metals.json
  //          |{"a": {}}
  //          |/a/Main.scala
  //          |class Evidence
  //          |
  //          |final case class DataType[T](number: Int, other: T) {
  //          |  def next(implicit evidence: Evidence): Int = number + 1
  //          |}
  //          |
  //          |object Example extends App {
  //          |
  //          |  implicit val evidence: Evidence = ???
  //          |
  //          |  def opts(i: Int)(implicit ev: Evidence) = Option(i)
  //          |  for {
  //          |    number <- Option(5)
  //          |    num2 <- opts(2)
  //          |    _ = "abc ".stripMargin
  //          |  } yield DataType(number, "").next
  //          |
  //          |  Option(5).map(DataType(_, "").next)
  //          |
  //          |}
  //          |""".stripMargin
  //     )
  //     _ <- server.didChangeConfiguration(
  //       """{
  //         |  "show-implicit-arguments": false,
  //         |  "show-implicit-conversions-and-classes": false,
  //         |  "show-inferred-type": true
  //         |}
  //         |""".stripMargin
  //     )
  //     _ <- server.didOpen("a/Main.scala")
  //     _ = assertNoDiagnostics()
  //     _ = assertNoDiff(
  //       client.workspaceDecorations,
  //       """|class Evidence
  //          |
  //          |final case class DataType[T](number: Int, other: T) {
  //          |  def next(implicit evidence: Evidence): Int = number + 1
  //          |}
  //          |
  //          |object Example extends App {
  //          |
  //          |  implicit val evidence: Evidence = ???
  //          |
  //          |  def opts(i: Int)(implicit ev: Evidence): Option[Int] = Option[Int](i)
  //          |  for {
  //          |    number: Int <- Option[Int](5)
  //          |    num2: Int <- opts(2)(evidence)
  //          |    _ = augmentString("abc ").stripMargin
  //          |  } yield DataType[String](number, "").next(evidence)
  //          |
  //          |  Option[Int](5).map[Int](DataType[String](_, "").next(evidence))
  //          |
  //          |}
  //          |""".stripMargin,
  //     )
  //     expectedParams = URLEncoder.encode(
  //       """["_empty_/Example.evidence."]"""
  //     )
  //     _ <- server.assertHoverAtLine(
  //       "a/Main.scala",
  //       "    num2 <- opts(2)@@",
  //       s"""|**Synthetics**:
  //           |
  //           |([evidence](command:metals.goto?$expectedParams))""".stripMargin,
  //     )
  //   } yield ()
  // }

  // test("issue") {
  //   for {
  //     _ <- initialize(
  //       s"""
  //          |/metals.json
  //          |{"a": {}}
  //          |/a/Main.scala
  //          |class Evidence
  //          |
  //          |final case class DataType[T](number: Int, other: T) {
  //          |  def next(implicit evidence: Evidence): Int = number + 1
  //          |}
  //          |
  //          |object Example extends App {
  //          |
  //          |  implicit val evidence: Evidence = ???
  //          |
  //          |  def opts(i: Int)(implicit ev: Evidence) = Option(i)
  //          |  for {
  //          |    number <- Option(5)
  //          |    num2 <- opts(2)
  //          |    _ = "abc ".stripMargin
  //          |  } yield DataType(number, "").next
  //          |
  //          |  Option(5).map(DataType(_, "").next)
  //          |
  //          |}
  //          |""".stripMargin
  //     )
  //     _ <- server.didChangeConfiguration(
  //       """{
  //         |  "show-implicit-arguments": false,
  //         |  "show-implicit-conversions-and-classes": false,
  //         |  "show-inferred-type": true
  //         |}
  //         |""".stripMargin
  //     )
  //     _ <- server.didOpen("a/Main.scala")
  //     // _ = assertNoDiagnostics()
  //     // _ = assertNoDiff(
  //     //   client.workspaceDecorations,
  //     //   """|class Evidence
  //     //      |
  //     //      |final case class DataType[T](number: Int, other: T) {
  //     //      |  def next(implicit evidence: Evidence): Int = number + 1
  //     //      |}
  //     //      |
  //     //      |object Example extends App {
  //     //      |
  //     //      |  implicit val evidence: Evidence = ???
  //     //      |
  //     //      |  def opts(i: Int)(implicit ev: Evidence): Option[Int] = Option[Int](i)
  //     //      |  for {
  //     //      |    number: Int <- Option[Int](5)
  //     //      |    num2: Int <- opts(2)
  //     //      |    _ = "abc ".stripMargin
  //     //      |  } yield DataType[String](number, "").next
  //     //      |
  //     //      |  Option[Int](5).map[Int](DataType[String](_, "").next)
  //     //      |
  //     //      |}
  //     //      |""".stripMargin
  //     // )
  //   } yield ()
  // }

  // test("type-aliases") {
  //   for {
  //     _ <- initialize(
  //       """|/metals.json
  //          |{
  //          |  "a": {}
  //          |}
  //          |/a/src/main/scala/Main.scala
  //          |object O {
  //          | type Foo3[T, R] = (T, R, "")
  //          | def hello: Option[(Int, String)] = {
  //          |  type Foo = (Int, String)
  //          |  type Foo2[T] = (T, String)
  //          |  def foo: Option[Foo] = ???
  //          |  def foo2: Option[Foo2[Int]] = Option((1, ""))
  //          |  def foo3: Option[Foo3[Int, String]] = Option((1, "", ""))
  //          |  for {
  //          |    a <- foo
  //          |    b <- foo2
  //          |    c <- foo3
  //          |  } yield a
  //          | }
  //          |}
  //          |""".stripMargin
  //     )
  //     _ <- server.didChangeConfiguration(
  //       """{
  //         |  "show-implicit-arguments": true,
  //         |  "show-implicit-conversions-and-classes": true,
  //         |  "show-inferred-type": true
  //         |}
  //         |""".stripMargin
  //     )
  //     _ <- server.didOpen("a/src/main/scala/Main.scala")
  //     _ <- server.didSave("a/src/main/scala/Main.scala")(identity)
  //     _ = assertNoDiagnostics()
  //     _ = assertNoDiff(
  //       client.workspaceDecorations,
  //       """|object O {
  //          | type Foo3[T, R] = (T, R, "")
  //          | def hello: Option[(Int, String)] = {
  //          |  type Foo = (Int, String)
  //          |  type Foo2[T] = (T, String)
  //          |  def foo: Option[Foo] = ???
  //          |  def foo2: Option[Foo2[Int]] = Option[(Int, String)]((1, ""))
  //          |  def foo3: Option[Foo3[Int, String]] = Option[(Int, String, )]((1, "", ""))
  //          |  for {
  //          |    a: Foo <- foo
  //          |    b: Foo2[Int] <- foo2
  //          |    c: Foo3[Int, String] <- foo3
  //          |  } yield a
  //          | }
  //          |}
  //          |""".stripMargin,
  //     )
  //   } yield ()
  // }

  // TODO: Uncomment after fixing for ValueOf
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
  //     _ = assertNoDiagnostics()
  //     _ <- server.assertInlayHints(
  //       "a/src/main/scala/Main.scala",
  //       """|object O {
  //          |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
  //          |  val m = foo[500](new ValueOf(...))
  //          |}
  //          |""".stripMargin,
  //     )
  //   } yield ()
  // }
}
