package tests.pc

import scala.meta.internal.pc.DecorationKind

import tests.BaseSyntheticDecorationsSuite

class SynthethicDecorationsSuite extends BaseSyntheticDecorationsSuite {

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
    kind = Some(DecorationKind.TypeParameter),
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
    kind = Some(DecorationKind.TypeParameter),
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
    kind = Some(DecorationKind.ImplicitParameter),
  )

  check(
    "implicit-conversion",
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = User(x.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  implicit def intToUser(x: Int): User = User(x.toString)
       |  val y: User = intToUser(1)
       |}
       |""".stripMargin,
    kind = Some(DecorationKind.ImplicitConversion),
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
    kind = Some(DecorationKind.ImplicitParameter),
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
    kind = Some(DecorationKind.ImplicitConversion),
  )

  // TODO: Examinate implicit conversion here and unignore
  check(
    "wrong-given-conversion".ignore,
    """|trait Xg:
       |  def doX: Int
       |trait Yg:
       |  def doY: String
       |given (using Xg): Yg with
       |  def doY = "7"
       |""".stripMargin,
    "",
  )

  checkInferredType(
    "basic",
    "123",
    "Int",
  )

  checkInferredType(
    "list",
    "List[Int](1,2,3)",
    "List[Int]",
  )

  checkInferredType(
    "two-param",
    """Map[Int, String]((1, "abc"))""",
    "Map[Int,String]",
    compat = Map(
      "3" -> """Map[Int, String]"""
    ),
  )

  checkInferredType(
    "tuple",
    "(123, 456)",
    "(Int, Int)",
  )

  checkInferredType(
    "import-needed",
    """List[String]("").toBuffer[String]""",
    "Buffer[String]",
  )

  checkInferredType(
    "lambda-type",
    "() => 123",
    "() => Int",
  )

  checkInferredType(
    "block",
    "{ val z = 123; z + 2}",
    "Int",
  )

  checkInferredType(
    "refined-type",
    "new Foo { type T = Int; type G = Long}",
    "Foo{type T = Int; type G = Long}",
    """|trait Foo {
       |  type T
       |  type G
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "refined-type1",
    "new Foo { type T = Int }",
    "Foo{type T = Int}",
    """|trait Foo {
       |  type T
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "refined-type2".tag(IgnoreScala2),
    """|new Foo {
       |  type T = Int
       |  val x = 0
       |  def y = 0
       |  var z = 0
       |}
       |""".stripMargin,
    "Foo{type T = Int; val x: Int; def y: Int; val z: Int; def z_=(x$1: Int): Unit}",
    """|trait Foo extends Selectable {
       |  type T
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "dealias",
    "new Foo().getT",
    "Int",
    """|class Foo() {
       |  type T = Int
       |  def getT: T = 1
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "dealias1",
    "getT",
    "T",
    """|type T = Int
       |def getT: T = 1
       |""".stripMargin,
  )

  checkInferredType(
    "dealias2".tag(IgnoreScala2),
    "Foo.getT",
    "T",
    """|object Foo {
       |  opaque type T = Int
       |  def getT: T = 1
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "dealias3",
    "O.get",
    "Int => Int",
    """|object O {
       | type M = Int
       | type W = M => Int
       | def get: W = ???
       |}
       |""".stripMargin,
    compat = Map(
      "2" -> "O.W"
    ),
  )

  checkInferredType(
    "dealias4".tag(IgnoreScala2),
    "O.get",
    "M => Int",
    """|object O {
       | opaque type M = Int
       | type W = M => Int
       | def get: W = ???
       |}
       |""".stripMargin,
  )

  checkInferredType(
    "dealias5",
    "get",
    "W => S.M",
    """|object S {
       |  type M = Int
       |} 
       |type W
       |def get: W => S.M = ???
       |""".stripMargin,
    compat = Map(
      "3" -> "W => Int"
    ),
  )

  check(
    "tuple",
    """|object Main {
       |  val x = (1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x = (1, 2)
       |}
       |""".stripMargin,
  )

  check(
    "explicit-tuple",
    """|object Main {
       |  val x = Tuple2.apply(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x = Tuple2.apply[Int, Int](1, 2)
       |}
       |""".stripMargin,
  )

  check(
    "explicit-tuple1",
    """|object Main {
       |  val x = Tuple2(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x = Tuple2[Int, Int](1, 2)
       |}
       |""".stripMargin,
  )

  check(
    "tuple-unapply",
    """|object Main {
       |  val (fst, snd) = (1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val (fst, snd) = (1, 2)
       |}
       |""".stripMargin,
  )

  check(
    "list-unapply",
    """|object Main {
       |  val hd :: tail = List(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val hd :: tail = List[Int](1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val hd ::[Int] tail = List[Int](1, 2)
           |}
           |""".stripMargin
    ),
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
       |  val x = List[Int](1, 2) match {
       |    case hd :: tail => hd
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val x = List[Int](1, 2) match {
           |    case hd ::[Int] tail => hd
           |  }
           |}
           |""".stripMargin
    ),
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
       |  val Foo(fst, snd) = Foo[Int](1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |case class Foo[A](x: A, y: A)
           |  val Foo[Int](fst, snd) = Foo[Int](1, 2)
           |}
           |""".stripMargin
    ),
  )

}
