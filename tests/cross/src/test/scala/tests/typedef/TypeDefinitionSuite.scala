package tests.typedef

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Location
import tests.pc.BasePcDefinitionSuite

class TypeDefinitionSuite extends BasePcDefinitionSuite {

  override def requiresJdkSources: Boolean = true

  override def requiresScalaLibrarySources: Boolean = true

  override def definitions(offsetParams: OffsetParams): List[Location] =
    presentationCompiler
      .typeDefinition(offsetParams)
      .get()
      .locations
      .asScala
      .toList

  check(
    "val",
    """|class <<TClass>>(i: Int)
       |
       |object Main {
       |  val ts@@t = new TClass(2)
       |}""".stripMargin,
    compat = Map(
      "2" ->
        """|class <<>>TClass(i: Int)
           |
           |object Main {
           |  val tst = new TClass(2)
           |}""".stripMargin
    )
  )

  check(
    "for",
    """|
       |object Main {
       |  for {
       |    x <- List(1)
       |    y <- 1.to(x)
       |    z = y + x
       |    if y < /*scala/Int# Int.scala*/@@x
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "for-flatMap",
    """|
       |object Main {
       |  for {
       |    x /*scala/Option# Option.scala*/@@<- Option(1)
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
       |    y /*scala/Option# Option.scala*/@@<- Option(x)
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
       |    /*scala/Option#WithFilter# Option.scala*/@@if y > 2
       |  } yield y
       |}
       |""".stripMargin
  )

  check(
    "constructor",
    """
      |class <<TClass>>(i: Int) {}
      |
      |object Main {
      | def tst(m: TClass): Unit = {}
      |
      |  tst(new T@@Class(2))
      |}""".stripMargin,
    compat = Map(
      "2" ->
        """|class <<>>TClass(i: Int) {}
           |
           |object Main {
           | def tst(m: TClass): Unit = {}
           |
           |  tst(new TClass(2))
           |}
           |""".stripMargin
    )
  )

  check(
    "function",
    """|
       |object Main {
       |  val increment: Int => Int = _ + 2
       |  incre/*scala/Int# Int.scala*/@@ment(1)
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
    "method",
    """|object Main {
       |  def tst(): Unit = {}
       |
       |  ts@@/*scala/Unit# Unit.scala*/t()
       |}""".stripMargin
  )

  check(
    "named-arg-multiple",
    """|object Main {
       |  def tst(par1: Int, par2: String, par3: Boolean): Unit = {}
       |
       |  tst(1, p/*scala/Boolean# Boolean.scala*/@@ar3 = true, par2 = "")
       |}""".stripMargin
  )

  check(
    "named-arg-reversed",
    """|object Main {
       |  def tst(par1: Int, par2: String): Unit = {}
       |
       |  tst(p/*scala/Predef.String# Predef.scala*/@@ar2 = "foo", par1 = 1)
       |}""".stripMargin
  )

  check(
    "named-arg-local",
    """|object Main {
       |  def foo(arg: Int): Unit = ()
       |
       |  foo(a/*scala/Int# Int.scala*/@@rg = 42)
       |}
       |""".stripMargin
  )

  check(
    "named-arg-global",
    """|object Main {
       |  assert(a/*scala/Boolean# Boolean.scala*/@@ssertion = true)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  assert(a/*scala/Boolean# Boolean.scala*/@@ssertion = true)
           |}
           |""".stripMargin
    )
  )

  check(
    "list",
    """|object Main {
       | List(1).hea/*scala/Int# Int.scala*/@@d
       |}
       |""".stripMargin
  )

  check(
    "class",
    """|object Main {
       | class <<F@@oo>>(val x: Int)
       |}
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|object Main {
           | class <<>>Foo(val x: Int)
           |}
           |""".stripMargin
    )
  )

  check(
    "val-keyword",
    """|object Main {
       | va@@l x = 42
       |}
       |""".stripMargin
  )

  check(
    "literal",
    """|object Main {
       | val x = 4/*scala/Int# Int.scala*/@@2
       |}
       |""".stripMargin
  )

  check(
    "if",
    """|object Main {
       | for {
       |   x <- List(1)
       |   i/*scala/collection/generic/FilterMonadic# FilterMonadic.scala*/@@f x > 1
       | } println(x)
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           | for {
           |   x <- List(1)
           |   i/*scala/collection/WithFilter# WithFilter.scala*/f x > 1
           | } println(x)
           |}
           |""".stripMargin
    )
  )

  check(
    "string",
    """|object Main {
       | "".stripS/*java/lang/String# String.java*/@@uffix("foo")
       |}
       |""".stripMargin
  )

  check(
    "method-generic",
    """|object Main {
       | def foo[<<T>>](param: T): T = para@@m
       |}
       |""".stripMargin,
    compat = Map(
      "2" ->
        """|object Main {
           | def foo[<<>>T](param: T): T = param
           |}
           |""".stripMargin
    )
  )

  check(
    "method-generic-result",
    """|object A {
       | def foo[T](param: T): T = param
       |}
       |object Main {
       | println(A.fo/*scala/Int# Int.scala*/@@o(2))
       |}
       |""".stripMargin
  )

  check(
    "apply",
    """|
       |object Main {
       |  /*scala/collection/immutable/List# List.scala*/@@List(1)
       |}
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|
           |object Main {
           |  /*scala/collection/immutable/List# List.scala*/List(1)
           |}
           |""".stripMargin,
      "3" ->
        """|
           |object Main {
           |  /*scala/collection/immutable/List# List.scala*/List(1)
           |}
           |""".stripMargin
    )
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
    "symbolic-infix",
    """|
       |object Main {
       |  val lst = 1 /*scala/collection/immutable/List# List.scala*/@@:: Nil
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
       |  List(1).map(/*scala/Int# Int.scala*/@@_ + 2)
       |}
       |""".stripMargin
  )

  check(
    "eta-2",
    """|
       |object Main {
       |  List(1).foldLeft(0)(_ + /*scala/Int# Int.scala*/@@_)
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
    "do-not-point-at ::",
    """|
       |class Main {
       |  val all = Option(42)./*scala/Int# Int.scala*/@@get :: List("1", "2")
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

}
