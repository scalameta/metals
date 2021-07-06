package tests.pc

class PcDefinitionSuite extends BasePcDefinitionSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

  check(
    "basic",
    """|
       |object Main {
       |  val <<>>abc = 42
       |  println(a@@bc)
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |object Main {
           |  val <<abc>> = 42
           |  println(abc)
           |}
           |""".stripMargin
    )
  )

  check(
    "for",
    """|
       |object Main {
       |  for {
       |    <<x>> <- List(1)
       |    y <- 1.to(x)
       |    z = y + x
       |    if y < @@x
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "for-flatMap",
    """|
       |object Main {
       |  for {
       |    x /*scala/Option#flatMap(). Option.scala*/@@<- Option(1)
       |    y <- Option(x)
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "for-map",
    """|
       |object Main {
       |  for {
       |    x <- Option(1)
       |    y /*scala/Option#map(). Option.scala*/@@<- Option(x)
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "for-withFilter",
    """|
       |object Main {
       |  for {
       |    x <- Option(1)
       |    y <- Option(x)
       |    /*scala/Option#withFilter(). Option.scala*/@@if y > 2
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "function",
    """|
       |object Main {
       |  val <<>>increment: Int => Int = _ + 2
       |  incre@@ment(1)
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |object Main {
           |  val <<increment>>: Int => Int = _ + 2
           |  increment(1)
           |}
           |""".stripMargin
    )
  )

  check(
    "tuple",
    // assert we don't go to `Tuple2.scala`
    """|
       |object Main {
       |  @@(1, 2)
       |}
       |""".stripMargin
  )

  check(
    "apply",
    """|
       |object Main {
       |  /*scala/collection/immutable/List.apply(). List.scala*/@@List(1)
       |}
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|
           |object Main {
           |  /*scala/collection/IterableFactory#apply(). Factory.scala*/List(1)
           |}
           |""".stripMargin,
      "3.0" ->
        """|
           |object Main {
           |  /*scala/collection/IterableFactory#apply(). Factory.scala*/List(1)
           |}
           |""".stripMargin
    )
  )

  check(
    "error",
    """|
       |object Main {
       |  /*scala/Predef.assert(). Predef.scala*//*scala/Predef.assert(+1). Predef.scala*/@@assert
       |}
       |""".stripMargin
  )

  check(
    "error2",
    """|
       |object Main {
       |  Predef./*scala/Predef.assert(). Predef.scala*//*scala/Predef.assert(+1). Predef.scala*/@@assert
       |}
       |""".stripMargin
  )

  check(
    "error3",
    """|
       |object Main {
       |  1./*scala/Predef.Ensuring#ensuring(). Predef.scala*//*scala/Predef.Ensuring#ensuring(+1). Predef.scala*//*scala/Predef.Ensuring#ensuring(+2). Predef.scala*//*scala/Predef.Ensuring#ensuring(+3). Predef.scala*/@@ensuring
       |}
       |""".stripMargin
  )

  check(
    "new",
    """|
       |object Main {
       |  ne@@w java.io.File("")
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|object Main {
           |  new/*java/io/File#`<init>`(+2). File.java*/ java.io.File("")
           |}
           |""".stripMargin
    )
  )

  check(
    "extends",
    """|
       |object Main ex@@tends java.io.Serializable {
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |object <<Main>> extends java.io.Serializable {
           |}
           |""".stripMargin
    )
  )

  check(
    "import1",
    """|
       |import scala.concurrent./*scala/concurrent/Future. Future.scala*/@@Future
       |object Main {
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |import scala.concurrent./*scala/concurrent/Future# Future.scala*//*scala/concurrent/Future. Future.scala*/@@Future
           |object Main {
           |}
           |""".stripMargin
    )
  )

  check(
    "import2",
    """|
       |imp@@ort scala.concurrent.Future
       |object Main {
       |}
       |""".stripMargin
  )

  check(
    "import3",
    """|
       |import scala.co@@ncurrent.Future
       |object Main {
       |}
       |""".stripMargin
  )

  check(
    "named-arg-local",
    """|
       |object Main {
       |  <<def foo(arg: Int): Unit = ()>>
       |
       |  foo(a@@rg = 42)
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |object Main {
           |  def foo(<<arg>>: Int): Unit = ()
           |
           |  foo(arg = 42)
           |}
           |""".stripMargin
    )
  )

  check(
    "named-arg-global",
    // NOTE(olafur) ideally we should navigate to the parameter symbol instead of the
    // enclosing method symbol, but I can live with this behavior.
    """|
       |object Main {
       |  assert(/*scala/Predef.assert(). Predef.scala*/@@assertion = true)
       |}
       |""".stripMargin,
    compat = Map(
      // in 3.0 here we obtain patched assert
      // see: https://github.com/scalameta/metals/issues/2918
      "3.0" ->
        """|
           |object Main {
           |  assert(/*scala/Predef.assert(+1). Predef.scala*/@@assertion = true)
           |}
           |""".stripMargin
    )
  )

  check(
    "symbolic-infix",
    """|
       |object Main {
       |  val lst = 1 /*scala/collection/immutable/List#`::`(). List.scala*/@@:: Nil
       |}
       |""".stripMargin
  )

  check(
    "colon",
    """|
       |object Main {
       |  val number@@: Int = 1
       |}
       |""".stripMargin,
    compat = Map(
      "3.0" ->
        """|
           |object Main {
           |  val <<number>>: Int = 1
           |}
           |""".stripMargin
    )
  )

  check(
    "package",
    """|
       |object Main {
       |  val n = ma@@th.max(1, 2)
       |}
       |""".stripMargin
  )

  check(
    "eta",
    """|
       |object Main {
       |  List(1).map(@@_ + 2)
       |}
       |""".stripMargin
  )

  check(
    "result-type",
    """|
       |object Main {
       |  def x: /*scala/Int# Int.scala*/@@Int = 42
       |}
       |""".stripMargin
  )

  check(
    "constructor",
    """|
       |class Main(x: /*scala/Int# Int.scala*/@@Int)
       |""".stripMargin
  )

  check(
    "case-class-apply".tag(IgnoreScala2),
    """|
       |case class Foo(<<a>>: Int, b: String)
       |class Main {
       |  Foo(@@a = 3, b = "42")
       |}
       |""".stripMargin
  )

  check(
    "case-class-copy".tag(IgnoreScala2),
    """|
       |case class Foo(<<a>>: Int, b: String)
       |class Main {
       |  Foo(2, "4").copy(@@a = 3, b = "42")
       |}
       |""".stripMargin
  )

  check(
    "do-not-point-at ::",
    """|
       |class Main {
       |  val all = Option(42)./*scala/Option#get(). Option.scala*/@@get :: List("1", "2")
       |}
       |""".stripMargin
  )
}
