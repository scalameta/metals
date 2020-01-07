package tests.pc

import funsuite.BeforeAll

object PcDefinitionSuite extends BasePcDefinitionSuite {

  override def beforeAll(context: BeforeAll): Unit = {
    indexJDK()
    indexScalaLibrary()
  }

  check(
    "basic",
    """|
       |object Main {
       |  val <<>>abc = 42
       |  println(a@@bc)
       |}
       |""".stripMargin
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
       |""".stripMargin
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
           |  /*scala/collection/immutable/List.apply(). List.scala*//*scala/collection/IterableFactory#apply(). Factory.scala*/List(1)
           |}
           |""".stripMargin
    )
  )

  check(
    "error",
    """|
       |object Main {
       |  /*scala/Predef.assert(+1). Predef.scala*//*scala/Predef.assert(). Predef.scala*/@@assert
       |}
       |""".stripMargin
  )

  check(
    "new",
    """|
       |object Main {
       |  ne@@w java.io.File("")
       |}
       |""".stripMargin
  )

  check(
    "extends",
    """|
       |object Main ex@@tends java.io.Serializable {
       |}
       |""".stripMargin
  )

  check(
    "import1",
    """|
       |import scala.concurrent./*scala/concurrent/Future. Future.scala*/@@Future
       |object Main {
       |}
       |""".stripMargin
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
       |""".stripMargin
  )

  check(
    "named-arg-global",
    // NOTE(olafur) ideally we should navigate to the parameter symbol instead of the
    // enclosing method symbol, but I can live with this behavior.
    """|
       |object Main {
       |  assert(/*scala/Predef.assert(). Predef.scala*/@@assertion = true)
       |}
       |""".stripMargin
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
       |""".stripMargin
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
}
