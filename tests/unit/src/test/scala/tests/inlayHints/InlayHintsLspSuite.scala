package tests.inlayHints

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseInlayHintsLspSuite

class InlayHintsLspSuite
    extends BaseInlayHintsLspSuite("implicits", V.scala213) {

  check(
    "all-synthetics",
    """|import scala.concurrent.Future
       |case class Location(city: String)
       |object Main{
       |  def hello()(implicit name: String, from: Location)/*: Unit<<scala/Unit#>>*/ = {
       |    println(s"Hello $$name from $${from.city}")
       |  }
       |  implicit val andy : String = "Andy"
       |
       |  def greeting()/*: Unit<<scala/Unit#>>*/ = {
       |    implicit val boston/*: Location<<(1:11)>>*/ = Location("Boston")
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/;    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |  }
       |  
       |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*(Char<<scala/math/Ordering.Char.>>)*/
       |  /*augmentString<<scala/Predef.augmentString().>>(*/"foo"/*)*/.map(c/*: Char<<scala/Char#>>*/ => c.toInt)
       |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
       |  Future{
       |    println("")
       |  }/*(ec<<(16:15)>>)*/
       |}
       |""".stripMargin,
    config = Some(
      """|"implicitArguments": {"enable": true},
         |"implicitConversions": {"enable": true},
         |"inferredTypes": {"enable": true},
         |"hintsInPatternMatch": {"enable": true}
         |""".stripMargin
    ),
  )

  check(
    "all-synthetics1",
    """|import scala.concurrent.Future
       |case class Location(city: String)
       |object Main{
       |  def hello()(implicit name: String, from: Location)/*: Unit<<scala/Unit#>>*/ = {
       |    println(s"Hello $$name from $${from.city}")
       |  }
       |  implicit val andy : String = "Andy"
       |
       |  def greeting()/*: Unit<<scala/Unit#>>*/ = {
       |    implicit val boston/*: Location<<(1:11)>>*/ = Location("Boston")
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/;    hello()/*(andy<<(6:15)>>, boston<<(9:17)>>)*/
       |  }
       |  
       |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*[Char<<scala/Char#>>]*//*(Char<<scala/math/Ordering.Char.>>)*/
       |  /*augmentString<<scala/Predef.augmentString().>>(*/"foo"/*)*/.map/*[Int<<scala/Int#>>]*/(c/*: Char<<scala/Char#>>*/ => c.toInt)
       |  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
       |  Future/*[Unit<<scala/Unit#>>]*/{
       |    println("")
       |  }/*(ec<<(16:15)>>)*/
       |}
       |""".stripMargin,
  )

  check(
    "single-option",
    """|object Main{
       |  def hello()(implicit name: String) = {
       |    println(s"Hello $$name!")
       |  }
       |  implicit val andy : String = "Andy"
       |  hello()/*(andy<<(4:15)>>)*/
       |  ("1" + "2").map(c => c.toDouble)
       |}
       |""".stripMargin,
    config = Some(
      """"implicitArguments": {"enable": true}"""
    ),
  )

  check(
    "single-option1",
    """|object Main{
       |  def hello()(implicit name: String) = {
       |    println(s"Hello $$name!")
       |  }
       |  implicit val andy : String = "Andy"
       |  hello()
       |  (/*augmentString<<scala/Predef.augmentString().>>(*/"1" + "2"/*)*/).map(c => c.toDouble)
       |}
       |""".stripMargin,
    config = Some(
      """"implicitConversions": {"enable": true}"""
    ),
  )

  check(
    "single-option2",
    """|object Main{
       |  def hello()(implicit name: String)/*: Unit<<scala/Unit#>>*/ = {
       |    println(s"Hello $$name!")
       |  }
       |  implicit val andy : String = "Andy"
       |  hello()
       |  ("1" + "2").map(c => c.toDouble)
       |}
       |""".stripMargin,
    config = Some(
      """"inferredTypes": {"enable": true}"""
    ),
  )

  check(
    "augment-string",
    """|object Main{
       |  /*augmentString<<scala/Predef.augmentString().>>(*//*augmentString<<scala/Predef.augmentString().>>(*/(/*augmentString<<scala/Predef.augmentString().>>(*/"1" + "2"/*)*/)
       |    .stripSuffix(".")/*)*/
       |    .stripSuffix("#")/*)*/
       |    .stripPrefix("_empty_.")
       |}
       |""".stripMargin,
    config = Some(
      """"implicitConversions": {"enable": true}"""
    ),
  )

  check(
    "inferred-type-various",
    """|object Main{
       |  val head/*: Double<<scala/Double#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Double<<scala/Double#>>]*/ = List/*[Double<<scala/Double#>>]*/(0.1, 0.2, 0.3)
       |  val List/*[Int<<scala/Int#>>]*/(l1/*: Int<<scala/Int#>>*/, l2/*: Int<<scala/Int#>>*/) = List/*[Int<<scala/Int#>>]*/(12, 13)
       |  println("Hello!")
       |  val abc/*: Int<<scala/Int#>>*/ = 123
       |  val tupleBound @ (one/*: String<<java/lang/String#>>*/, two/*: String<<java/lang/String#>>*/) = ("1", "2")
       |  val tupleExplicit/*: (String<<java/lang/String#>>, String<<java/lang/String#>>)*/ = Tuple2/*[String<<java/lang/String#>>, String<<java/lang/String#>>]*/("1", "2")
       |  val tupleExplicitApply/*: (String<<java/lang/String#>>, String<<java/lang/String#>>)*/ = Tuple2.apply/*[String<<java/lang/String#>>, String<<java/lang/String#>>]*/("1", "2")
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
       |    println(s"Hello $$name!")
       |  }
       |  def convert()(name: String => String = _ => "")/*: Unit<<scala/Unit#>>*/ = {
       |    println(s"Hello $$name!")
       |  }
       |  val tpl1/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (123, 1)
       |  val tpl2/*: (Int<<scala/Int#>>, Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (123, 1, 3)
       |  val func0/*: () => Int<<scala/Int#>>*/ = () => 2
       |  val func1/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = (a : Int) => a + 2
       |  val func2/*: (Int<<scala/Int#>>, Int<<scala/Int#>>) => Int<<scala/Int#>>*/ = (a : Int, b: Int) => a + b
       |  val complex/*: List<<scala/collection/immutable/List#>>[(Double<<scala/Double#>>, Int<<scala/Int#>>)]*/ = tail.zip/*[Int<<scala/Int#>>]*/(1 to 12)
       |  for{
       |    i/*: (Double<<scala/Double#>>, Int<<scala/Int#>>)*/ <- complex
       |    c/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = func1
       |  } i match {
       |    case (b/*: Double<<scala/Double#>>*/, c: Int) =>
       |    case a/*: (Double<<scala/Double#>>, Int<<scala/Int#>>)*/ =>
       |  }
       |  convert(){str/*: String<<scala/Predef.String#>>*/ => str.stripMargin}
       |  convert(){str : String => str.stripMargin}
       |}
       |""".stripMargin,
    config = Some(
      """|"typeParameters": {"enable": true},
         |"inferredTypes": {"enable": true},
         |"hintsInPatternMatch": {"enable": true}
         |""".stripMargin
    ),
  )

  check(
    "inferred-type-complex",
    """|import scala.concurrent.ExecutionContextExecutorService
       |
       |trait A
       |trait B
       |
       |class Main(implicit ec: ExecutionContextExecutorService){
       |  // structural types
       |  val anon1/*: A<<(2:6)>>*/ = new A {}
       |  val anon2/*: A<<(2:6)>>{def a: Int<<scala/Int#>>}*/ = new A { def a/*: Int<<scala/Int#>>*/ = 123 }
       |  val anon3/*: A<<(2:6)>> with B<<(3:6)>>*/ = new A with B {}
       |  val anon4/*: A<<(2:6)>>{def a: Int<<scala/Int#>>; def b: Int<<scala/Int#>>}*/ = new A {def a/*: Int<<scala/Int#>>*/ = 123; def b/*: Int<<scala/Int#>>*/ = 123}
       |  // existential type
       |  val job/*: Future<<java/util/concurrent/Future#>>[_ <: Object<<java/lang/Object#>>]*/ = ec.submit(new Runnable {
       |     override def run(): Unit = {}
       |  })
       |  val runnable/*: Runnable<<java/lang/Runnable#>>*/ = new Runnable {
       |    override def run(): Unit = {}
       |  }
       |}
       |""".stripMargin,
    config = Some(
      """"inferredTypes": {"enable": true}"""
    ),
  )

  check(
    "inferred-type-hkt",
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
       |  def serve[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel]()/*: Resource<<cats/effect/Resource#>>[F<<(16:12)>>,Unit<<scala/Unit#>>]*/ =
       |    for {
       |      logger <- mkLogger[F]
       |    } yield ()
       |
       |  def run(args: List[String]): IO[ExitCode] = ???
       |}
       |""".stripMargin,
    config = Some(
      """"inferredTypes": {"enable": true}"""
    ),
    dependencies = List("org.typelevel::cats-effect:2.4.0"),
  )

  // Ignored: worksheet support not available in this configuration
  test("worksheet".ignore) {
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
      _ <- server.didChangeConfiguration("{}")
      _ <- server.didOpen("a/Main.worksheet.sc")
      _ <- server.assertInlayHints(
        "a/Main.worksheet.sc",
        """|def method(implicit str: String) = str + str
           |implicit val name: String = "Susan".stripMargin/* // : String = "Susan"| name: String = "Susan" |*/ 
           |val greeting = s"Hello $$name"/* // : String = "Hello $name"| greeting: String = "Hello $name" |*/ 
           |method/* // : String = "SusanSusan"| res0: String = "SusanSusan" |*/
           |""".stripMargin,
        withTooltip = true,
      )
      _ <- server.didChangeConfiguration(
        """|{"inlayHints": {
           |  "inferredTypes": {"enable":true},
           |  "implicitConversions": {"enable":true},
           |  "implicitArguments": {"enable":true},
           |  "typeParameters": {"enable":true}
           |}}
           |""".stripMargin
      )
      _ <- server.assertInlayHints(
        "a/Main.worksheet.sc",
        """|def method(implicit str: String)/*: String<<java/lang/String#>>*/ = str + str
           |implicit val name: String = /*augmentString<<scala/Predef.augmentString().>>(*/"Susan"/*)*/.stripMargin/* // : String = "Susan"*/
           |val greeting/*: String<<java/lang/String#>>*/ = s"Hello $$name"/* // : String = "Hello $name"*/
           |method/*(name<<(1:13)>>)*//* // : String = "SusanSusan"*/
           |""".stripMargin,
      )
    } yield ()
  }

  check(
    "for-yield",
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
       |  def opts(i: Int)(implicit ev: Evidence)/*: Option<<scala/Option#>>[Int<<scala/Int#>>]*/ = Option/*[Int<<scala/Int#>>]*/(i)
       |  for {
       |    number/*: Int<<scala/Int#>>*/ <- Option/*[Int<<scala/Int#>>]*/(5)
       |    num2/*: Int<<scala/Int#>>*/ <- opts(2)/*(evidence<<(8:15)>>)*/
       |    _ = /*augmentString<<scala/Predef.augmentString().>>(*/"abc "/*)*/.stripMargin
       |  } yield DataType/*[String<<java/lang/String#>>]*/(number, "").next/*(evidence<<(8:15)>>)*/
       |
       |  Option/*[Int<<scala/Int#>>]*/(5).map/*[Int<<scala/Int#>>]*/(DataType/*[String<<java/lang/String#>>]*/(_, "").next/*(evidence<<(8:15)>>)*/)
       |}
       |""".stripMargin,
  )

  check(
    "type-aliases",
    """|object O {
       | type Foo3[T, R] = (T, R, "")
       | def hello: Option[(Int, String)] = {
       |  type Foo = (Int, String)
       |  type Foo2[T] = (T, String)
       |  def foo: Option[Foo] = ???
       |  def foo2: Option[Foo2[Int]] = Option/*[(Int<<scala/Int#>>, String<<java/lang/String#>>)]*/((1, ""))
       |  def foo3: Option[Foo3[Int, String]] = Option/*[(Int<<scala/Int#>>, String<<java/lang/String#>>, "")]*/((1, "", ""))
       |  for {
       |    a/*: Foo<<(3:7)>>*/ <- foo
       |    b/*: Foo2<<(4:7)>>[Int<<scala/Int#>>]*/ <- foo2
       |    c/*: Foo3<<(1:6)>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ <- foo3
       |  } yield a
       | }
       |}
       |""".stripMargin,
  )

  check(
    "value-of",
    """|object O {
       |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
       |  val m = foo[500]/*(new ValueOf(...))*/
       |}
       |""".stripMargin,
    config = Some(""""implicitArguments": {"enable": true}"""),
  )

  check(
    "i4970",
    """|import cats.effect.IO
       |
       |object Test {
       |  def foo(str: fs2.Stream[IO, Int]) =
       |    str.compile.to(/*supportsIterableFactory<<fs2/CollectorPlatform#supportsIterableFactory().>>(*/Set/*)*/)
       |}
       |""".stripMargin,
    config = Some(""""implicitConversions": {"enable": true}"""),
    dependencies = List("co.fs2::fs2-core:3.9.0"),
  )

}
