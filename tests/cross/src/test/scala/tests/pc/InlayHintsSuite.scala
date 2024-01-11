package tests.pc

import tests.BaseInlayHintsSuite

class InlayHintsSuite extends BaseInlayHintsSuite {

  check(
    "local",
    """|object Main {
       |  def foo() = {
       |    implicit val imp: Int = 2
       |    def addOne(x: Int)(implicit one: Int) = x + one
       |    val x = addOne(1)
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  def foo()/*: Unit<<scala/Unit#>>*/ = {
       |    implicit val imp: Int = 2
       |    def addOne(x: Int)(implicit one: Int)/*: Int<<scala/Int#>>*/ = x + one
       |    val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(imp<<(3:17)>>)*/
       |  }
       |}
       |""".stripMargin
  )

  check(
    "type-params",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(List(1))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T)/*: T<<(2:12)>>*/ = t
       |  val x/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = hello/*[List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]]*/(List/*[Int<<scala/Int#>>]*/(1))
       |}
       |""".stripMargin
  )

  check(
    "type-params2",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(Map((1,"abc")))
       |}
       |""".stripMargin,
    """|object Main {
       |  def hello[T](t: T)/*: T<<(2:12)>>*/ = t
       |  val x/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]*/ = hello/*[Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]]*/(Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/((1,"abc")))
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def hello[T](t: T)/*: T<<(2:12)>>*/ = t
           |  val x/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>, String<<java/lang/String#>>]*/ = hello/*[Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>, String<<java/lang/String#>>]]*/(Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/((1,"abc")))
           |}
           |""".stripMargin
    )
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
       |  def addOne(x: Int)(implicit one: Int)/*: Int<<scala/Int#>>*/ = x + one
       |  val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(imp<<(3:15)>>)*/
       |}
       |""".stripMargin
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
       |  val y: User = /*intToUser<<(3:15)>>(*/1/*)*/
       |}
       |""".stripMargin
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
       |  def addOne(x: Int)(using one: Int)/*: Int<<scala/Int#>>*/ = x + one
       |  val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(imp<<(3:15)>>)*/
       |}
       |""".stripMargin
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
       |  val y: User = /*intToUser<<(3:8)>>(*/1/*)*/
       |}
       |""".stripMargin
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
       |  def doY/*: String<<scala/Predef.String#>>*/ = "7"
       |""".stripMargin
  )

  check(
    "basic",
    """|object Main {
       |  val foo = 123
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo/*: Int<<scala/Int#>>*/ = 123
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
       |  val foo/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List[Int](123)
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
       |  def m/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = 1 ::/*[Int<<scala/Int#>>]*/ List/*[Int<<scala/Int#>>]*/(1)
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
       |  val foo/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]*/ = Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/((1, "abc"))
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val foo/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>, String<<java/lang/String#>>]*/ = Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/((1, "abc"))
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
       |  val foo/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = (123, 456)
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
       |  val foo/*: Buffer<<scala/collection/mutable/Buffer#>>[String<<scala/Predef.String#>>]*/ = List[String]("").toBuffer[String]
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val foo/*: Buffer<<scala/collection/mutable/Buffer#>>[String<<java/lang/String#>>]*/ = List[String]("").toBuffer[String]
           |}
           |""".stripMargin
    )
  )

  check(
    "lambda-type",
    """|object Main {
       |  val foo = () => 123
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo/*: () => Int<<scala/Int#>>*/ = () => 123
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
       |  val foo/*: Int<<scala/Int#>>*/ = { val z/*: Int<<scala/Int#>>*/ = 123; z + 2}
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
       |  val c/*: Foo<<(2:8)>>{type T = Int<<scala/Int#>>; type G = Long<<scala/Long#>>}*/ = new Foo { type T = Int; type G = Long}
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
       |  val c/*: Foo<<(2:8)>>{type T = Int<<scala/Int#>>}*/ = new Foo { type T = Int }
       |  val d/*: Foo<<(2:8)>>{type T = Int<<scala/Int#>>}*/ = c
       |}
       |""".stripMargin
  )

  check(
    "refined-types3".tag(IgnoreScala2),
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
       | val c/*: Int<<scala/Int#>>*/ = new Foo().getT
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
       |  val c/*: T<<(2:7)>>*/ = getT
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
       |val c/*: T<<(2:14)>>*/ = Foo.getT
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
       |val m/*: Int<<scala/Int#>> => Int<<scala/Int#>>*/ = O.get
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
       |val m/*: M<<(2:13)>> => Int<<scala/Int#>>*/ = O.get
       |""".stripMargin
  )

  check(
    "explicit-tuple",
    """|object Main {
       |  val x = Tuple2.apply(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = Tuple2.apply/*[Int<<scala/Int#>>, Int<<scala/Int#>>]*/(1, 2)
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
       |  val x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = Tuple2/*[Int<<scala/Int#>>, Int<<scala/Int#>>]*/(1, 2)
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
       |  val (fst/*: Int<<scala/Int#>>*/, snd/*: Int<<scala/Int#>>*/) = (1, 2)
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
       |  val hd/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val hd/*: Int<<scala/Int#>>*/ ::/*[Int<<scala/Int#>>]*/ tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(1, 2)
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
       |  val x/*: Int<<scala/Int#>>*/ = List/*[Int<<scala/Int#>>]*/(1, 2) match {
       |    case hd/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => hd
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val x/*: Int<<scala/Int#>>*/ = List/*[Int<<scala/Int#>>]*/(1, 2) match {
           |    case hd/*: Int<<scala/Int#>>*/ ::/*[Int<<scala/Int#>>]*/ tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => hd
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
       |  val Foo(fst/*: Int<<scala/Int#>>*/, snd/*: Int<<scala/Int#>>*/) = Foo/*[Int<<scala/Int#>>]*/(1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |case class Foo[A](x: A, y: A)
           |  val Foo/*[Int<<scala/Int#>>]*/(fst/*: Int<<scala/Int#>>*/, snd/*: Int<<scala/Int#>>*/) = Foo/*[Int<<scala/Int#>>]*/(1, 2)
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
       |  val m/*: Int<<scala/Int#>>*/ = foo[500]/*(new ValueOf(...))*/
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
       |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*[Char<<scala/Char#>>]*//*(Char<<scala/math/Ordering.Char.>>)*/
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
       |  List/*[Int<<scala/Int#>>]*/(1).collect/*[Int<<scala/Int#>>]*/ { case x/*: Int<<scala/Int#>>*/ => x }
       |  val x: PartialFunction[Int, Int] = { 
       |    case 1 => 2 
       |  }
       |}
       |""".stripMargin
  )

  check(
    "val-def-with-bind".tag(IgnoreForScala3CompilerPC),
    """|object O {
       |  val tupleBound @ (one, two) = ("1", "2")
       |}
       |""".stripMargin,
    """|object O {
       |  val tupleBound @ (one/*: String<<java/lang/String#>>*/, two/*: String<<java/lang/String#>>*/) = ("1", "2")
       |}
       |""".stripMargin
  )

  check(
    "val-def-with-bind-and-comment".tag(IgnoreForScala3CompilerPC),
    """|object O {
       |  val tupleBound /* comment */ @ (one, two) = ("1", "2")
       |}
       |""".stripMargin,
    """|object O {
       |  val tupleBound /* comment */ @ (one/*: String<<java/lang/String#>>*/, two/*: String<<java/lang/String#>>*/) = ("1", "2")
       |}
       |""".stripMargin
  )

  check(
    "complex".tag(IgnoreScalaVersion.forLessThan("2.12.16")),
    """|object ScalatestMock {
       |  class SRF
       |  implicit val subjectRegistrationFunction: SRF = new SRF()
       |  class Position
       |  implicit val here: Position = new Position()
       |  implicit class StringTestOps(name: String) {
       |    def should(right: => Unit)(implicit config: SRF): Unit = ()
       |    def in(f: => Unit)(implicit pos: Position): Unit = ()
       |  }
       |  implicit def instancesString: Eq[String] with Semigroup[String] = ???
       |}
       |
       |trait Eq[A]
       |trait Semigroup[A]
       |
       |class DemoSpec {
       |  import ScalatestMock._
       |
       |  "foo" should {
       |    "checkThing1" in {
       |      checkThing1[String]
       |    }
       |    "checkThing2" in {
       |      checkThing2[String]
       |    }
       |  }
       |
       |  "bar" should {
       |    "checkThing1" in {
       |      checkThing1[String]
       |    }
       |  }
       |
       |  def checkThing1[A](implicit ev: Eq[A]) = ???
       |  def checkThing2[A](implicit ev: Eq[A], sem: Semigroup[A]) = ???
       |}
       |""".stripMargin,
    """|object ScalatestMock {
       |  class SRF
       |  implicit val subjectRegistrationFunction: SRF = new SRF()
       |  class Position
       |  implicit val here: Position = new Position()
       |  implicit class StringTestOps(name: String) {
       |    def should(right: => Unit)(implicit config: SRF): Unit = ()
       |    def in(f: => Unit)(implicit pos: Position): Unit = ()
       |  }
       |  implicit def instancesString: Eq[String] with Semigroup[String] = ???
       |}
       |
       |trait Eq[A]
       |trait Semigroup[A]
       |
       |class DemoSpec {
       |  import ScalatestMock._
       |
       |  /*StringTestOps<<(6:17)>>(*/"foo"/*)*/ should {
       |    /*StringTestOps<<(6:17)>>(*/"checkThing1"/*)*/ in {
       |      checkThing1[String]/*(instancesString<<(10:15)>>)*/
       |    }/*(here<<(5:15)>>)*/
       |    /*StringTestOps<<(6:17)>>(*/"checkThing2"/*)*/ in {
       |      checkThing2[String]/*(instancesString<<(10:15)>>, instancesString<<(10:15)>>)*/
       |    }/*(here<<(5:15)>>)*/
       |  }/*(subjectRegistrationFunction<<(3:15)>>)*/
       |
       |  /*StringTestOps<<(6:17)>>(*/"bar"/*)*/ should {
       |    /*StringTestOps<<(6:17)>>(*/"checkThing1"/*)*/ in {
       |      checkThing1[String]/*(instancesString<<(10:15)>>)*/
       |    }/*(here<<(5:15)>>)*/
       |  }/*(subjectRegistrationFunction<<(3:15)>>)*/
       |
       |  def checkThing1[A](implicit ev: Eq[A])/*: Nothing<<scala/Nothing#>>*/ = ???
       |  def checkThing2[A](implicit ev: Eq[A], sem: Semigroup[A])/*: Nothing<<scala/Nothing#>>*/ = ???
       |}
       |""".stripMargin
  )

  check(
    "import-rename",
    """|import scala.collection.{AbstractMap => AB}
       |import scala.collection.{Set => S}
       |
       |object Main {
       |  def test(d: S[Int], f: S[Char]): AB[Int, String] = {
       |    val x = d.map(_.toString)
       |    val y = f
       |    ???
       |  }
       |  val x = test(Set(1), Set('a'))
       |}
       |""".stripMargin,
    """|package `import-rename`
       |import scala.collection.{AbstractMap => AB}
       |import scala.collection.{Set => S}
       |
       |object Main {
       |  def test(d: S[Int], f: S[Char]): AB[Int, String] = {
       |    val x/*: S<<scala/collection/Set#>>[String<<java/lang/String#>>]*/ = d.map/*[String<<java/lang/String#>>, S<<scala/collection/Set#>>[String<<java/lang/String#>>]]*/(_.toString)/*(canBuildFrom<<scala/collection/Set.canBuildFrom().>>)*/
       |    val y/*: S<<scala/collection/Set#>>[Char<<scala/Char#>>]*/ = f
       |    ???
       |  }
       |  val x/*: AB<<scala/collection/AbstractMap#>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ = test(Set/*[Int<<scala/Int#>>]*/(1), Set/*[Char<<scala/Char#>>]*/('a'))
       |}
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|package `import-rename`
           |import scala.collection.{AbstractMap => AB}
           |import scala.collection.{Set => S}
           |
           |object Main {
           |  def test(d: S[Int], f: S[Char]): AB[Int, String] = {
           |    val x/*: S<<scala/collection/Set#>>[String<<java/lang/String#>>]*/ = d.map/*[String<<java/lang/String#>>]*/(_.toString)
           |    val y/*: S<<scala/collection/Set#>>[Char<<scala/Char#>>]*/ = f
           |    ???
           |  }
           |  val x/*: AB<<scala/collection/AbstractMap#>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ = test(Set/*[Int<<scala/Int#>>]*/(1), Set/*[Char<<scala/Char#>>]*/('a'))
           |}
           |""".stripMargin,
      "3" ->
        """|package `import-rename`
           |import scala.collection.{AbstractMap => AB}
           |import scala.collection.{Set => S}
           |
           |object Main {
           |  def test(d: S[Int], f: S[Char]): AB[Int, String] = {
           |    val x/*: S<<scala/collection/Set#>>[String<<java/lang/String#>>]*/ = d.map/*[String<<java/lang/String#>>]*/(_.toString)
           |    val y/*: S<<scala/collection/Set#>>[Char<<scala/Char#>>]*/ = f
           |    ???
           |  }
           |  val x/*: AB<<scala/collection/AbstractMap#>>[Int<<scala/Int#>>, String<<scala/Predef.String#>>]*/ = test(Set/*[Int<<scala/Int#>>]*/(1), Set/*[Char<<scala/Char#>>]*/('a'))
           |}
           |""".stripMargin
    )
  )
}
