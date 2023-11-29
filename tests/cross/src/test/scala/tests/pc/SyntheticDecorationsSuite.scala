package tests.pc

import scala.meta.internal.pc.DecorationKind

import tests.BaseSyntheticDecorationsSuite

class SyntheticDecorationsSuite extends BaseSyntheticDecorationsSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreForScala3CompilerPC
  )

  check(
    "type-params",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(List(1))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello[List[Int]](List[Int](1))
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.TypeParameter)
  )

  check(
    "type-params2",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(Map((1,"abc")))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello[Map[Int,String]](Map[Int, String]((1,"abc")))
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def hello[T](t: T) = t
           |  val x = hello[Map[Int, String]](Map[Int, String]((1,"abc")))
           |}
           |""".stripMargin
    ),
    kind = Some(DecorationKind.TypeParameter)
  )

  check(
    "implicit-param",
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(implicit one: Int) = x + one
       |  val x = addOne(1)
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(implicit one: Int) = x + one
       |  val x = addOne(1)(imp)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitParameter)
  )

  check(
    "implicit-conversion",
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = new User(x.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = new User(x.toString)
       |  val y: User = intToUser(1)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitConversion)
  )

  check(
    "using-param".tag(IgnoreScala2),
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(using one: Int) = x + one
       |  val x = addOne(1)
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit val imp: Int = 2
       |  def addOne(x: Int)(using one: Int) = x + one
       |  val x = addOne(1)(imp)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitParameter)
  )

  check(
    "given-conversion".tag(IgnoreScala2),
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = intToUser(1)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitConversion)
  )

  check(
    "given-conversion2".tag(IgnoreScala2),
    """|trait Xg:
       |  def doX: Int
       |trait Yg:
       |  def doY: String
       |given (using Xg): Yg with
       |  def doY = "7"
       |""".stripMargin,
    """|trait Xg:
       |  def doX: Int
       |trait Yg:
       |  def doY: String
       |given (using Xg): Yg with
       |  def doY: String = "7"
       |""".stripMargin
  )

  check(
    "basic",
    """|object Main {
       |  val foo = 123
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: Int = 123
       |}
       |""".stripMargin
  )

  check(
    "list",
    """|object Main {
       |  val foo = List[Int](123)
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: List[Int] = List[Int](123)
       |}
       |""".stripMargin
  )

  check(
    "list2",
    """|object O {
       |  def m = 1 :: List(1)
       |}
       |""".stripMargin,
    """|object O {
       |  def m: List[Int] = 1 ::[Int] List[Int](1)
       |}
       |""".stripMargin
  )

  check(
    "two-param",
    """|object Main {
       |  val foo = Map((1, "abc"))
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: Map[Int,String] = Map[Int, String]((1, "abc"))
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val foo: Map[Int, String] = Map[Int, String]((1, "abc"))
           |}
           |""".stripMargin
    )
  )

  check(
    "tuple",
    """|object Main {
       |  val foo = (123, 456)
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: (Int, Int) = (123, 456)
       |}
       |""".stripMargin
  )

  check(
    "import-needed",
    """|object Main {
       |  val foo = List[String]("").toBuffer[String]
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: Buffer[String] = List[String]("").toBuffer[String]
       |}
       |""".stripMargin
  )

  check(
    "lambda-type",
    """|object Main {
       |  val foo = () => 123
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: () => Int = () => 123
       |}
       |""".stripMargin
  )

  check(
    "block",
    """|object Main {
       |  val foo = { val z = 123; z + 2}
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo: Int = { val z: Int = 123; z + 2}
       |}
       |""".stripMargin
  )

  check(
    "refined-types",
    """|object O{
       |  trait Foo {
       |    type T
       |    type G
       |  }
       |
       |  val c = new Foo { type T = Int; type G = Long}
       |}
       |""".stripMargin,
    """|object O{
       |  trait Foo {
       |    type T
       |    type G
       |  }
       |
       |  val c: Foo{type T = Int; type G = Long} = new Foo { type T = Int; type G = Long}
       |}
       |""".stripMargin
  )

  check(
    "refined-types2",
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |  val c = new Foo { type T = Int }
       |  val d = c
       |}
       |""".stripMargin,
    """|object O{
       |  trait Foo {
       |    type T
       |  }
       |  val c: Foo{type T = Int} = new Foo { type T = Int }
       |  val d: Foo{type T = Int} = c
       |}
       |""".stripMargin
  )

  check(
    "refined-types4".tag(IgnoreScala2),
    """|trait Foo extends Selectable {
       |  type T
       |}
       |
       |val c = new Foo {
       |  type T = Int
       |  val x = 0
       |  def y = 0
       |  var z = 0
       |}
       |""".stripMargin,
    """|trait Foo extends Selectable {
       |  type T
       |}
       |
       |val c: Foo{type T = Int; val x: Int; def y: Int; val z: Int; def z_=(x$1: Int): Unit} = new Foo {
       |  type T = Int
       |  val x: Int = 0
       |  def y: Int = 0
       |  var z: Int = 0
       |}
       |""".stripMargin
  )

  check(
    "dealias",
    """|class Foo() {
       |  type T = Int
       |  def getT: T = 1
       |}
       |
       |object O {
       | val c = new Foo().getT
       |}
       |""".stripMargin,
    """|class Foo() {
       |  type T = Int
       |  def getT: T = 1
       |}
       |
       |object O {
       | val c: Int = new Foo().getT
       |}
       |""".stripMargin
  )

  check(
    "dealias2",
    """|object Foo {
       |  type T = Int
       |  def getT: T = 1
       |  val c = getT
       |}
       |""".stripMargin,
    """|object Foo {
       |  type T = Int
       |  def getT: T = 1
       |  val c: T = getT
       |}
       |""".stripMargin
  )

  check(
    "dealias3".tag(IgnoreScala2),
    """|object Foo:
       |  opaque type T = Int
       |  def getT: T = 1
       |val c = Foo.getT
       |""".stripMargin,
    """|object Foo:
       |  opaque type T = Int
       |  def getT: T = 1
       |val c: T = Foo.getT
       |""".stripMargin
  )

  check(
    "dealias4".tag(IgnoreScala2),
    """|object O:
       | type M = Int
       | type W = M => Int
       | def get: W = ???
       |
       |val m = O.get
       |""".stripMargin,
    """|object O:
       | type M = Int
       | type W = M => Int
       | def get: W = ???
       |
       |val m: Int => Int = O.get
       |""".stripMargin
  )

  check(
    "dealias5".tag(IgnoreScala2),
    """|object O:
       | opaque type M = Int
       | type W = M => Int
       | def get: W = ???
       |
       |val m = O.get
       |""".stripMargin,
    """|object O:
       | opaque type M = Int
       | type W = M => Int
       | def get: W = ???
       |
       |val m: M => Int = O.get
       |""".stripMargin
  )

  check(
    "tuple",
    """|object Main {
       |  val x = (1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x: (Int, Int) = (1, 2)
       |}
       |""".stripMargin
  )

  check(
    "explicit-tuple",
    """|object Main {
       |  val x = Tuple2.apply(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x: (Int, Int) = Tuple2.apply[Int, Int](1, 2)
       |}
       |""".stripMargin
  )

  check(
    "explicit-tuple1",
    """|object Main {
       |  val x = Tuple2(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x: (Int, Int) = Tuple2[Int, Int](1, 2)
       |}
       |""".stripMargin
  )

  check(
    "tuple-unapply",
    """|object Main {
       |  val (fst, snd) = (1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val (fst: Int, snd: Int) = (1, 2)
       |}
       |""".stripMargin
  )

  check(
    "list-unapply",
    """|object Main {
       |  val hd :: tail = List(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val hd: Int :: tail: List[Int] = List[Int](1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val hd: Int ::[Int] tail: List[Int] = List[Int](1, 2)
           |}
           |""".stripMargin
    )
  )

  check(
    "list-match",
    """|object Main {
       |  val x = List(1, 2) match {
       |    case hd :: tail => hd
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  val x: Int = List[Int](1, 2) match {
       |    case hd: Int :: tail: List[Int] => hd
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val x: Int = List[Int](1, 2) match {
           |    case hd: Int ::[Int] tail: List[Int] => hd
           |  }
           |}
           |""".stripMargin
    )
  )

  check(
    "case-class-unapply",
    """|object Main {
       |case class Foo[A](x: A, y: A)
       |  val Foo(fst, snd) = Foo(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |case class Foo[A](x: A, y: A)
       |  val Foo(fst: Int, snd: Int) = Foo[Int](1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |case class Foo[A](x: A, y: A)
           |  val Foo[Int](fst: Int, snd: Int) = Foo[Int](1, 2)
           |}
           |""".stripMargin
    )
  )

  check(
    "valueOf".tag(IgnoreScalaVersion.forLessThan("2.13.0")),
    """|object O {
       |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
       |  val m = foo[500]
       |}
       |""".stripMargin,
    """|object O {
       |  def foo[Total <: Int](implicit total: ValueOf[Total]): Int = total.value
       |  val m: Int = foo[500](new ValueOf(...))
       |}
       |""".stripMargin
  )

  check(
    "case-class1",
    """|object O {
       |case class A(x: Int, g: Int)(implicit y: String)
       |}
       |""".stripMargin,
    """|object O {
       |case class A(x: Int, g: Int)(implicit y: String)
       |}
       |""".stripMargin
  )

  check(
    "ord",
    """|object Main {
       |  val ordered = "acb".sorted
       |}
       |""".stripMargin,
    """|object Main {
       |  val ordered: String = augmentString("acb").sorted[Char](Char)
       |}
       |""".stripMargin
  )

  check(
    "partial-fun".tag(IgnoreScalaVersion.forLessThan("2.13.0")),
    """|object Main {
       |  List(1).collect { case x => x }
       |  val x: PartialFunction[Int, Int] = { 
       |    case 1 => 2 
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  List[Int](1).collect[Int] { case x: Int => x }
       |  val x: PartialFunction[Int, Int] = { 
       |    case 1 => 2 
       |  }
       |}
       |""".stripMargin
  )

  check(
    "val-def-with-bind",
    """|object O {
       |  val tupleBound @ (one, two) = ("1", "2")
       |}
       |""".stripMargin,
    """|object O {
       |  val tupleBound @ (one: String, two: String) = ("1", "2")
       |}
       |""".stripMargin
  )

  check(
    "val-def-with-bind-and-comment",
    """|object O {
       |  val tupleBound /* comment */ @ (one, two) = ("1", "2")
       |}
       |""".stripMargin,
    """|object O {
       |  val tupleBound /* comment */ @ (one: String, two: String) = ("1", "2")
       |}
       |""".stripMargin
  )
}
