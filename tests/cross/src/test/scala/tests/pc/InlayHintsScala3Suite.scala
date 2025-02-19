package tests.pc

import tests.BaseInlayHintsSuite

class InlayHintsScala3Suite extends BaseInlayHintsSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  check(
    "using-param",
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
       |  def addOne(x: Int)(using one: Int)/*: Int<<scala/Int#>>*/ = x + one
       |  val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(using imp<<(3:15)>>)*/
       |}
       |""".stripMargin
  )

  check(
    "given-conversion",
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = 1
       |}
       |""".stripMargin,
    """|case class User(name: String)
       |object Main {
       |  given intToUser: Conversion[Int, User] = User(_.toString)
       |  val y: User = /*intToUser<<(3:8)>>(*/1/*)*/
       |}
       |""".stripMargin
  )

  check(
    "given-conversion2",
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
       |  def doY/*: String<<scala/Predef.String#>>*/ = "7"
       |""".stripMargin
  )

  check(
    "refined-types3",
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
       |val c/*: Foo<<(1:6)>>{type T = Int<<scala/Int#>>; val x: Int<<scala/Int#>>; def y: Int<<scala/Int#>>; val z: Int<<scala/Int#>>; def z_=(x$1: Int<<scala/Int#>>): Unit<<scala/Unit#>>}*/ = new Foo {
       |  type T = Int
       |  val x/*: Int<<scala/Int#>>*/ = 0
       |  def y/*: Int<<scala/Int#>>*/ = 0
       |  var z/*: Int<<scala/Int#>>*/ = 0
       |}
       |""".stripMargin
  )

  check(
    "dealias3",
    """|object Foo:
       |  opaque type T = Int
       |  def getT: T = 1
       |val c = Foo.getT
       |""".stripMargin,
    """|object Foo:
       |  opaque type T = Int
       |  def getT: T = 1
       |val c/*: T<<(2:14)>>*/ = Foo.getT
       |""".stripMargin
  )

  check(
    "dealias4",
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
       |val m/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = O.get
       |""".stripMargin
  )

  check(
    "dealias5",
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
       |val m/*: M<<(2:13)>> => Int<<scala/Int#>>*/ = O.get
       |""".stripMargin
  )

  // NOTE: We don't show inlayHints for anonymous given instances
  check(
    "anonymous-given",
    """|package example
       |
       |trait Ord[T]:
       |  def compare(x: T, y: T): Int
       |
       |given intOrd: Ord[Int] with
       |  def compare(x: Int, y: Int) =
       |    if x < y then -1 else if x > y then +1 else 0
       |
       |given Ord[String] with
       |  def compare(x: String, y: String) =
       |    x.compare(y)
       |
       |""".stripMargin,
    """|package example
       |
       |trait Ord[T]:
       |  def compare(x: T, y: T): Int
       |
       |given intOrd: Ord[Int] with
       |  def compare(x: Int, y: Int)/*: Int<<scala/Int#>>*/ =
       |    if x < y then -1 else if x > y then +1 else 0
       |
       |given Ord[String] with
       |  def compare(x: String, y: String)/*: Int<<scala/Int#>>*/ =
       |    /*augmentString<<scala/Predef.augmentString().>>(*/x/*)*/.compare(y)
       |
       |""".stripMargin
  )

  // TODO: Add a separate option for hints for context bounds
  check(
    "context-bounds1",
    """|package example
       |object O {
       |  given Int = 1
       |  def test[T: Ordering](x: T)(using Int) = ???
       |  test(1)
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  given Int = 1
       |  def test[T: Ordering](x: T)(using Int)/*: Nothing<<scala/Nothing#>>*/ = ???
       |  test/*[Int<<scala/Int#>>]*/(1)/*(using Int<<scala/math/Ordering.Int.>>, given_Int<<(2:8)>>)*/
       |}
       |""".stripMargin
  )

  check(
    "context-bounds3",
    """|package example
       |object O {
       |  def test[T: Ordering](x: T)(using Int) = ???
       |  test(1)
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  def test[T: Ordering](x: T)(using Int)/*: Nothing<<scala/Nothing#>>*/ = ???
       |  test/*[Int<<scala/Int#>>]*/(1)/*(using Int<<scala/math/Ordering.Int.>>)*/
       |}
       |""".stripMargin
  )

  check(
    "quotes",
    """|package example
       |import scala.quoted.*
       |object O:
       |  inline def foo[T]: List[String] = ${fooImpl[T]}
       |  def fooImpl[T: Type](using Quotes): Expr[List[String]] = ???
       |""".stripMargin,
    """|package example
       |import scala.quoted.*
       |object O:
       |  inline def foo[T]: List[String] = ${fooImpl[T]}
       |  def fooImpl[T: Type](using Quotes): Expr[List[String]] = ???
       |""".stripMargin
  )

  check(
    "quotes1",
    """|package example
       |import scala.quoted.*
       |object O:
       |  def matchTypeImpl[T: Type](param1: Expr[T])(using Quotes) =
       |    import quotes.reflect.*
       |    Type.of[T] match
       |      case '[f] =>
       |        val fr = TypeRepr.of[T]
       |""".stripMargin,
    """|package example
       |import scala.quoted.*
       |object O:
       |  def matchTypeImpl[T: Type](param1: Expr[T])(using Quotes)/*: Unit<<scala/Unit#>>*/ =
       |    import quotes.reflect.*
       |    Type.of[T] match
       |      case '[f] =>
       |        val fr/*: TypeRepr<<scala/quoted/Quotes#reflectModule#TypeRepr#>>*/ = TypeRepr.of[T]/*(using evidence$1<<(3:21)>>)*/
       |""".stripMargin
  )

  check(
    "quotes2",
    """|package example
       |import scala.quoted.*
       |object O:
       |  def rec[A : Type](using Quotes): List[String] =
       |    Type.of[A] match
       |      case '[field *: fields] => ???
       |""".stripMargin,
    """|package example
       |import scala.quoted.*
       |object O:
       |  def rec[A : Type](using Quotes): List[String] =
       |    Type.of[A] match
       |      case '[field *: fields] => ???
       |""".stripMargin
  )

}
