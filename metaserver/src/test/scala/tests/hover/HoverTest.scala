package tests.hover

object HoverTest extends BaseHoverTest {

  check(
    "val assignment",
    """
      |object a {
      |  val <<x>> = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "val x: List[Some[Int]]"
  )

  check(
    "val assignment type annotation",
    """
      |object b {
      |  val <<x>>: List[Option[Int]] = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "val x: List[Option[Int]]"
  )

  check(
    "var assignment",
    """
      |object c {
      |  var <<x>> = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "var x: List[Some[Int]]"
  )

  check(
    "var assignment type annotation",
    """
      |object d {
      |  var <<x>>: List[Option[Int]] = List(Some(1), Some(2), Some(3))
      |}
    """.stripMargin,
    "var x: List[Option[Int]]"
  )

  check(
    "select",
    """
      |object e {
      |  val x = List(1, 2, 3)
      |  <<x>>.mkString
      |}
    """.stripMargin,
    "val x: List[Int]"
  )

  checkMissing( // TODO(olafur) fall back to tokenizer
    "literal Int",
    """
      |object f {
      |  val x = <<42>>
      |}
    """.stripMargin,
  )

  check(
    "case class apply",
    """
      |object g {
      |  case class User(name: String, age: Int)
      |  val user = <<User>>("test", 42)
      |}
    """.stripMargin,
    "object User"
  )

  check(
    "case class parameters",
    """
      |object h {
      |  case class User(<<name>>: String, age: Int)
      |}
    """.stripMargin,
    "val name: String"
  )

  check(
    "def",
    """
      |object i {
      |  def <<test>>(x: String, y: List[Int]) = y.mkString.length
      |}
    """.stripMargin,
    "def test(x: String, y: List[Int]): Int"
  )

  check(
    "def with type parameters",
    """
      |object i2 {
      |  def <<test>>[T](lst: List[T]) = lst.tail
      |}
    """.stripMargin,
    "def test[T](lst: List[T]): List[T]"
  )

  check(
    "curried def",
    """
      |object i3 {
      |  def <<curry>>[T](init: T)(f: T => T): T =  ???
      |}
    """.stripMargin,
    "def curry[T](init: T)(f: T => T): T"
  )

  check(
    "def call site",
    """
      |object j {
      |  def test(x: String, y: List[Int]) = y.mkString.length
      |  <<test>>("foo", Nil)
      |}
    """.stripMargin,
    "def test(x: String, y: List[Int]): Int"
  )

  checkMissing(
    "keywords",
    """
      |cl<<a>>ss A(x: Int, y: String)
    """.stripMargin
  )

  checkMissing(
    "comments",
    """
      |class B(x: Int, y: String) // <<good>>
    """.stripMargin
  )

  check(
    "class",
    """
      |class <<C>>(x: Int, y: String)
    """.stripMargin,
    "class C"
  )

  check(
    "tuple literal",
    """
      |object l {
      |  val <<increment>>: (Int, String) = ???
      |}
    """.stripMargin,
    "val increment: (Int, String)"
  )

  //  val x: Tuple2[]
  check(
    "TupleN",
    """
      |object m {
      |  val <<increment>>: Tuple2[Int, String] = ???
      |}
    """.stripMargin,
    "val increment: (Int, String)"
  )

  check(
    "function literal",
    """
      |object n {
      |  val <<increment>>: Int => Int = _ + 1
      |}
    """.stripMargin,
    "val increment: Int => Int"
  )

  check(
    "FunctionN",
    """
      |object o {
      |  val <<increment>>: Function[Int, String] = _.toString
      |}
    """.stripMargin,
    "val increment: Function[Int, String]"
  )

  // TODO(olafur) named arguments are not handled in InteractiveSemanticdb
  checkMissing(
    "named args",
    """
      |object p {
      |  val foo = Left(<<value>> = 2)
      |}
    """.stripMargin
  )

  check(
    "symbolic infix",
    """
      |object q {
      |  type :+:[A, B] = Map[A, B]
      |  val <<infix>>: :+:[Map[Int, String], :+:[Int, String]] = ???
      |}
    """.stripMargin,
    "val infix: Map[Int, String] :+: Int :+: String"
  )

  check(
    "pattern",
    """
      |object r {
      |  List(1) match { case <<x>> :: Nil => }
      |}
    """.stripMargin,
    "val x: Int"
  )

  check(
    "pattern 2",
    """
      |object r2 {
      |  List(1) match { case List(<<x>>) => }
      |}
    """.stripMargin,
    "val x: Int"
  )

  check(
    "imports",
    """
      |import scala.concurrent.<<Future>>
      |object s {
      |  val x: Future[Int] = Future.successful(1)
      |}
    """.stripMargin,
    "object Future" // TODO(olafur) handle Symbol.Multi
  )

  check(
    "params",
    """
      |object v {
      |  List(1).foreach(<<x>> => println(x))
      |}
    """.stripMargin,
    "Int"
  )

  check(
    "for comprehension",
    """
      |object t {
      |  for {
      |    x <- List(1)
      |    z <- 1.to(x)
      |  } yield <<z>>
      |}
    """.stripMargin,
    "Int"
  )

  check(
    "package",
    """
      |object w {
      |  <<scala>>.Left(1)
      |}
    """.stripMargin,
    "package scala"
  )

  check(
    "package object",
    """
      |package object <<pkg>>
    """.stripMargin,
    // TODO(olafur) make <<math>>.max(1, 2) resolve to package object math
    "package object pkg"
  )

  check(
    "type alias",
    """
      |object v {
      |  type <<F>>[T] = Option[T]
      |}
    """.stripMargin,
    "type F[T] = Option[T]"
  )

  check(
    "abstract type alias",
    """
      |object v2 {
      |  type <<F>>[T] <: Any
      |}
    """.stripMargin,
    "type F"
  )
}
