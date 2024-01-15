package tests.highlight

import tests.BaseDocumentHighlightSuite

class Scala3DocumentHighlightSuite extends BaseDocumentHighlightSuite {

  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  check(
    "enum1",
    """|enum FooEnum:
       | case <<Ba@@r>>, Baz
       |val bar = FooEnum.<<Bar>>
       |""".stripMargin
  )

  check(
    "enum2",
    """|enum FooEnum:
       | case <<Bar>>, Baz
       |val bar = FooEnum.<<Ba@@r>>
       |""".stripMargin
  )

  check(
    "transparent1",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def <<foo>>(i: Int): Foo = new Bar
       |val iii = 123
       |val bar = <<f@@oo>>(iii)
       |""".stripMargin
  )

  check(
    "transparent2",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def <<f@@oo>>(i: Int): Foo = new Bar
       |val iii = 123
       |val bar = <<foo>>(iii)
       |""".stripMargin
  )

  check(
    "transparent3",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def foo(i: Int): Foo = new Bar
       |val <<ii@@i>> = 123
       |val bar = foo(<<iii>>)
       |""".stripMargin
  )

  check(
    "transparent4",
    """|trait Foo
       |class Bar extends Foo
       |
       |transparent inline def foo(i: Int): Foo = new Bar
       |val <<iii>> = 123
       |val bar = foo(<<i@@ii>>)
       |""".stripMargin
  )

  check(
    "recursive-inline1",
    """|inline def <<po@@wer>>(x: Double, n: Int): Double =
       |  if n == 0 then 1.0
       |  else if n == 1 then x
       |  else
       |    val y = <<power>>(x, n / 2)
       |    if n % 2 == 0 then y * y else y * y * x
       |""".stripMargin
  )

  check(
    "recursive-inline2",
    """|inline def <<power>>(x: Double, n: Int): Double =
       |  if n == 0 then 1.0
       |  else if n == 1 then x
       |  else
       |    val y = <<po@@wer>>(x, n / 2)
       |    if n % 2 == 0 then y * y else y * y * x
       |""".stripMargin
  )

  check(
    "extension-params",
    """|extension (<<sb@@d>>: String)
       |  def double = <<sbd>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin
  )

  check(
    "extension-params-ref",
    """|extension (<<sbd>>: String)
       |  def double = <<sb@@d>> + <<sbd>>
       |  def double2 = <<sbd>> + <<sbd>>
       |end extension
       |""".stripMargin
  )

  check(
    "extension-type-param",
    """|extension [T](<<x@@s>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<xs>>
       |end extension
       |""".stripMargin
  )

  check(
    "extension-type-param-ref",
    """|extension [T](<<xs>>: List[T])
       |  def double = <<xs>> ++ <<xs>>
       |  def double2 = <<xs>> ++ <<x@@s>>
       |end extension
       |""".stripMargin
  )

  check(
    "extension-with-type",
    """|object Mincase:
       |  extension [X](x: X)
       |    def <<foobar>>(): Unit = ???
       |
       |  val x = 1.<<foo@@bar>>()
       |  val y = (1: Int).<<foobar>>()
       |""".stripMargin
  )

  check(
    "extension-complex",
    """|object Extensions:
       |
       |  extension [A, B](<<eit@@hers>>: Seq[Either[A, B]])
       |    def sequence = <<eithers>>.partitionMap(identity) match
       |      case (Nil, rights)       => Right(rights)
       |      case (firstLeft :: _, _) => Left(firstLeft)
       |    def sequence2 = <<eithers>>.partitionMap(identity) match
       |      case (Nil, rights)       => Right(rights)
       |      case (firstLeft :: _, _) => Left(firstLeft)
       |
       |  extension (map: Map[String, String])
       |    def getOrLeft(key: String): Either[String, String] =
       |      map.get(key) match
       |        case None        => Left(s"Missing ${key} in }")
       |        case Some(value) => Right(value)
       |""".stripMargin
  )

  check(
    "given-synthetic1",
    """|given (usi@@ng i: Int): Double = 4.0
       |val a = given_Double""".stripMargin
  )

  check(
    "given-synthetic2",
    """|given (using i: Int): Double = 4.0
       |val a = <<given_Doub@@le>>""".stripMargin
  )

  check(
    "given-synthetic3",
    """|given Int = 10
       |val a = <<giv@@en_Int>>""".stripMargin
  )

  check(
    "given-synthetic4",
    """|given <<I@@nt>> = 10
       |val a = given_Int""".stripMargin
  )

  check(
    "given-not-synthetic1",
    """|given <<`giv@@en_D`>>: Double = 4.0
       |val a = <<`given_D`>>""".stripMargin
  )

  check(
    "given-not-synthetic2",
    """|given <<`given_D`>>:Double = 4.0
       |val a = <<`giv@@en_D`>>""".stripMargin
  )

  check(
    "extension-with-type-param1",
    """|extension [<<E@@F>>](xs: List[<<EF>>])
       |    def double(ys: List[<<EF>>]) = xs ++ ys
       |    def double2(ys: List[<<EF>>]) = xs ++ ys
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param2",
    """|extension [EF, <<E@@M>>](xs: Either[<<EM>>, EF])
       |    def id() = xs
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param3",
    """|extension [<<EF>>](xs: List[<<E@@F>>])
       |    def double(ys: List[<<EF>>]) = xs ++ ys
       |    def double2(ys: List[<<EF>>]) = xs ++ ys
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param4",
    """|val i: <<Int>> = 3
       |extension (xs: List[<<In@@t>>])
       |  def id() = xs
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param5",
    """|extension [<<EF>>](xs: List[<<EF>>])
       |    def double(ys: List[<<E@@F>>]) = xs ++ ys
       |    def double2(ys: List[<<EF>>]) = xs ++ ys
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param6",
    """|extension [EF](xs: List[EF])
       |    def double(<<y@@s>>: List[EF]) = xs ++ <<ys>>
       |    def double2(ys: List[EF]) = xs ++ ys
       |end extension""".stripMargin
  )

  check(
    "extension-with-type-param7",
    """|extension [EF](<<xs>>: List[EF])
       |    def double(ys: List[EF]) = <<x@@s>> ++ ys
       |    def double2(ys: List[EF]) = <<xs>> ++ ys
       |end extension""".stripMargin
  )

  check(
    "enum-cases",
    """|enum MyOption:
       |  case <<My@@Some>>(value: Int)
       |  case MyNone
       |
       |val alpha = MyOption.<<MySome>>(1)
       |""".stripMargin
  )

  check(
    "enum-cases2",
    """|enum MyOption:
       |  case <<My@@Some>>[U](value: U)
       |  case MyNone
       |
       |val alpha = MyOption.<<MySome>>(1)
       |""".stripMargin
  )

  check(
    "type-params-in-enum",
    """|enum MyOption[+<<A@@A>>]:
       |  case MySome(value: <<AA>>)
       |  case MyNone
       |""".stripMargin
  )

  check(
    "type-params-in-enum2",
    """|enum MyOption[+<<AA>>]:
       |  case MySome(value: <<A@@A>>)
       |  case MyNone
       |""".stripMargin
  )

  check(
    "type-params-in-enum3",
    """|enum MyOption[<<AA>>](v: <<AA>>):
       |  def get: <<A@@A>> = ???
       |  case MySome[AA](value: AA) extends MyOption[Int](1)
       |""".stripMargin
  )

  check(
    "type-params-in-enum4",
    """|enum MyOption[+<<AA>>]:
       |  def get: <<A@@A>> = ???
       |  case MySome(value: <<AA>>)
       |  case MyNone
       |""".stripMargin
  )

  check(
    "type-params-in-enum5",
    """|enum MyOption[AA]:
       |  def get: AA = ???
       |  case MySome[<<AA>>](value: <<A@@A>>) extends MyOption[Int]
       |""".stripMargin
  )

  check(
    "i5630",
    """|class MyIntOut(val value: Int)
       |object MyIntOut:
       |  extension (i: MyIntOut) def <<uneven>> = i.value % 2 == 1
       |
       |val a = MyIntOut(1)
       |val m = a.<<un@@even>>
       |""".stripMargin
  )

  check(
    "i5630-2",
    """|class MyIntOut(val value: Int)
       |object MyIntOut:
       |  extension (i: MyIntOut) def <<uneven>>(u: Int) = i.value % 2 == 1
       |
       |val a = MyIntOut(1).<<un@@even>>(3)
       |""".stripMargin
  )

  check(
    "i5630-infix",
    """|class MyIntOut(val value: Int)
       |object MyIntOut:
       |  extension (i: MyIntOut) def <<++>>(u: Int) = i.value + u
       |
       |val a = MyIntOut(1) <<+@@+>> 3
       |""".stripMargin
  )

  check(
    "i5921-1",
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<multiply>>(k: Logarithm): Logarithm = ???
       |
       |object Test:
       |  val in: Logarithms.Logarithm = ???
       |  in.<<multi@@ply>>(in)
       |""".stripMargin
  )

  check(
    "i5921-2",
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<mu@@ltiply>>(k: Logarithm): Logarithm = ???
       |
       |object Test:
       |  val in: Logarithms.Logarithm = ???
       |  in.<<multiply>>(in)
       |""".stripMargin
  )

  check(
    "i5921-3",
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<multiply>>(k: Logarithm): Logarithm = ???
       |  (2.0).<<mult@@iply>>(1.0)
       |""".stripMargin
  )

  check(
    "i5921-4",
    """|object Logarithms:
       |  opaque type Logarithm = Double
       |  extension [K](vmap: Logarithm)
       |    def <<mult@@iply>>(k: Logarithm): Logarithm = ???
       |  (2.0).<<multiply>>(1.0)
       |""".stripMargin
  )

  check(
    "i5977",
    """
      |sealed trait ExtensionProvider {
      |  extension [A] (self: A) {
      |    def typeArg[B <: A]: B
      |    def <<inferredTypeArg>>[C](value: C): C
      |  }
      |}
      |
      |object Repro {
      |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
      |
      |  usage[Int](_.<<infe@@rredTypeArg>>("str"))
      |  usage[Int](_.<<inferredTypeArg>>[String]("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>[String]("str"))
      |}
      |""".stripMargin
  )

  check(
    "i5977-1",
    """
      |sealed trait ExtensionProvider {
      |  extension [A] (self: A) {
      |    def typeArg[B <: A]: B
      |    def <<inferredTypeArg>>[C](value: C): C
      |  }
      |}
      |
      |object Repro {
      |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
      |
      |  usage[Int](_.<<inferredTypeArg>>("str"))
      |  usage[Int](_.<<infe@@rredTypeArg>>[String]("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>[String]("str"))
      |}
      |""".stripMargin
  )

  check(
    "i5977-2",
    """
      |sealed trait ExtensionProvider {
      |  extension [A] (self: A) {
      |    def typeArg[B <: A]: B
      |    def <<inferredTypeArg>>[C](value: C): C
      |  }
      |}
      |
      |object Repro {
      |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
      |
      |  usage[Int](_.<<inferredTypeArg>>("str"))
      |  usage[Int](_.<<inferredTypeArg>>[String]("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferr@@edTypeArg>>("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>[String]("str"))
      |}
      |""".stripMargin
  )

  check(
    "i5977-3",
    """
      |sealed trait ExtensionProvider {
      |  extension [A] (self: A) {
      |    def typeArg[B <: A]: B
      |    def <<inferredTypeArg>>[C](value: C): C
      |  }
      |}
      |
      |object Repro {
      |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
      |
      |  usage[Int](_.<<inferredTypeArg>>("str"))
      |  usage[Int](_.<<inferredTypeArg>>[String]("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferred@@TypeArg>>[String]("str"))
      |}
      |""".stripMargin
  )

  check(
    "i5977-4",
    """
      |sealed trait ExtensionProvider {
      |  extension [A] (self: A) {
      |    def typeArg[B <: A]: B
      |    def <<inferre@@dTypeArg>>[C](value: C): C
      |  }
      |}
      |
      |object Repro {
      |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
      |
      |  usage[Int](_.<<inferredTypeArg>>("str"))
      |  usage[Int](_.<<inferredTypeArg>>[String]("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>("str"))
      |  usage[Option[Int]](_.typeArg[Some[Int]].value.<<inferredTypeArg>>[String]("str"))
      |}
      |""".stripMargin
  )

}
