package tests.pc

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Location

class PcDefinitionSuite extends BasePcDefinitionSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

  override def definitions(offsetParams: OffsetParams): List[Location] =
    presentationCompiler
      .definition(offsetParams)
      .get()
      .locations()
      .asScala
      .toList

  check(
    "basic",
    """|
       |object Main {
       |  val <<>>abc = 42
       |  println(a@@bc)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
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
       |    <<>>x <- List(1)
       |    y <- 1.to(x)
       |    z = y + x
       |    if y < @@x
       |  } yield y
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  for {
           |    <<x>> <- List(1)
           |    y <- 1.to(x)
           |    z = y + x
           |    if y < x
           |  } yield y
           |}
           |""".stripMargin
    )
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
      "3" ->
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
      "3" ->
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
       |""".stripMargin,
    compat = Map(
      "3" ->
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
       |  def foo(<<>>arg: Int): Unit = ()
       |
       |  foo(a@@rg = 42)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
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
    "named-arg-multiple",
    """|object Main {
       |  def tst(par1: Int, par2: String, <<>>par3: Boolean): Unit = {}
       |
       |  tst(1, p@@ar3 = true, par2 = "")
       |}""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def tst(par1: Int, par2: String, <<par3>>: Boolean): Unit = {}
           |
           |  tst(1, p@@ar3 = true, par2 = "")
           |}""".stripMargin
    )
  )

  check(
    "named-arg-reversed",
    """|object Main {
       |  def tst(par1: Int, <<>>par2: String): Unit = {}
       |
       |  tst(pa@@r2 = "foo", par1 = 1)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def tst(par1: Int, <<par2>>: String): Unit = {}
           |
           |  tst(par2 = "foo", par1 = 1)
           |}
           |""".stripMargin
    )
  )

  check(
    "named-arg-global",
    """|object Main {
       |  assert(/*scala/Predef.assert().(assertion) Predef.scala*/@@assertion = true)
       |}
       |""".stripMargin,
    compat = Map(
      // in 3.0 here we obtain patched assert
      // see: https://github.com/scalameta/metals/issues/2918
      "3" ->
        """|object Main {
           |  assert(/*scala/Predef.assert(+1).(assertion) Predef.scala*/assertion = true)
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
      "3" ->
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
       |  List(1).map(<<>>@@_ + 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|
           |object Main {
           |  List(1).map(@@_ + 2)
           |}
           |""".stripMargin
    )
  )

  check(
    "eta-2",
    """|
       |object Main {
       |  List(1).foldLeft(0)(_ + <<>>@@_)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  List(1).foldLeft(0)(_ + _)
           |}
           |""".stripMargin
    )
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
    "case-class-apply",
    """|
       |case class Foo(<<>>a: Int, b: String)
       |class Main {
       |  Foo(@@a = 3, b = "42")
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|
           |case class Foo(<<a>>: Int, b: String)
           |class Main {
           |  Foo(@@a = 3, b = "42")
           |}
           |""".stripMargin
    )
  )

  check(
    "case-class-copy",
    """|
       |case class Foo(<<>>a: Int, b: String)
       |class Main {
       |  Foo(2, "4").copy(@@a = 3, b = "42")
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|
           |case class Foo(<<a>>: Int, b: String)
           |class Main {
           |  Foo(2, "4").copy(@@a = 3, b = "42")
           |}
           |""".stripMargin
    )
  )

  check(
    "do-not-point-at ::",
    """|
       |class Main {
       |  val all = Option(42)./*scala/Option#get(). Option.scala*/@@get :: List("1", "2")
       |}
       |""".stripMargin
  )

  check(
    "synthetic-definition-case-class",
    """|
       |class Main {
       |  case class <<>>User(name: String, age: Int)
       |  def hello(u: User): Unit = ()
       |  hello(Us@@er())
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|
           |class Main {
           |  case class <<User>>(name: String, age: Int)
           |  def hello(u: User): Unit = ()
           |  hello(User())
           |}
           |""".stripMargin
    )
  )

  check(
    "synthetic-definition-class-constructor",
    """|
       |class Main {
       |  class <<>>User(name: String, age: Int)
       |  def hello(u: User): Unit = ()
       |  hello(new Us@@er())
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|
           |class Main {
           |  class <<User>>(name: String, age: Int)
           |  def hello(u: User): Unit = ()
           |  hello(new Us@@er())
           |}
           |""".stripMargin
    )
  )
  check(
    "no-definition-1",
    """|
       |object Main {
       |  @@
       |  def foo() = {
       |    // this is a comment
       |  }
       |  println(foo())
       |}
       |""".stripMargin
  )

  check(
    "no-definition-2",
    """|
       |object Main {
       |  def foo() = {
       |    @@// this is a comment
       |  }
       |  println(foo())
       |}
       |""".stripMargin
  )

  check(
    "no-definition-3",
    """|
       |object Main {
       |  def foo() = {
       |    // th@@is is a comment
       |  }
       |  println(foo())
       |}
       |""".stripMargin
  )

  check(
    "derives-def".tag(IgnoreScala2),
    """|
       |import scala.deriving.Mirror
       |
       |trait <<Show>>[A]:
       |  def show(a: A): String
       |
       |object Show:
       |  inline def derived[T](using Mirror.Of[T]): Show[T] = new Show[T]:
       |    override def show(a: T): String = a.toString
       |
       |case class Box[A](value: A) derives Sh@@ow
       |
       |""".stripMargin
  )

  check(
    "macro".tag(IgnoreScala2),
    """|
       |
       |import scala.quoted.*
       |
       |def myMacroImpl(using Quotes) =
       |  import quotes.reflect.Ident
       |  def foo = ??? match
       |    case x: I/*scala/quoted/Quotes#reflectModule#Ident# Quotes.scala*/@@dent => x
       |
       |  def bar: Ident = foo
       |
       |  ???
       |
       |""".stripMargin
  )

  check(
    "enum-type-param".tag(IgnoreScala2),
    """|package a
       |enum MyOption[+<<AA>>]:
       |  case MySome(value: A@@A)
       |  case MyNone
       |""".stripMargin
  )

  check(
    "i5630".tag(IgnoreScala2),
    """|class MyIntOut(val value: Int)
       |object MyIntOut:
       |  extension (i: MyIntOut) def <<uneven>> = i.value % 2 == 1
       |
       |val a = MyIntOut(1).un@@even
       |""".stripMargin
  )

  check(
    "i5921".tag(IgnoreScala2),
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<multiply>>(k: Logarithm): Logarithm = ???
       |
       |object Test:
       |  val in: Logarithms.Logarithm = ???
       |  in.multi@@ply(in)
       |""".stripMargin
  )

  check(
    "i5921-1".tag(IgnoreScala2),
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<multiply>>(k: Logarithm): Logarithm = ???
       |  (2.0).mult@@iply(1.0)
       |""".stripMargin
  )

  check(
    "i5921-2".tag(IgnoreScala2),
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def multiply(k: Logarithm): Logarithm = ???
       |  val <<vv>> = 1.0
       |  (2.0).multiply(v@@v)
       |""".stripMargin
  )

}
