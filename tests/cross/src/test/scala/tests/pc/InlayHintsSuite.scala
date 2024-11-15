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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  def foo()/*: Unit<<scala/Unit#>>*/ = {
           |    implicit val imp: Int = 2
           |    def addOne(x: Int)(implicit one: Int)/*: Int<<scala/Int#>>*/ = x + one
           |    val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(using imp<<(3:17)>>)*/
           |  }
           |}
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|case class User(name: String)
           |object Main {
           |  implicit val imp: Int = 2
           |  def addOne(x: Int)(implicit one: Int)/*: Int<<scala/Int#>>*/ = x + one
           |  val x/*: Int<<scala/Int#>>*/ = addOne(1)/*(using imp<<(3:15)>>)*/
           |}
           |""".stripMargin
    )
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
       |  def m/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = 1 :: List/*[Int<<scala/Int#>>]*/(1)
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
       |  val (local, _) = ("", 1.0)
       |}
       |""".stripMargin,
    """|object Main {
       |  val (fst/*: Int<<scala/Int#>>*/, snd/*: Int<<scala/Int#>>*/) = (1, 2)
       |  val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
       |}
       |""".stripMargin,
    hintsInPatternMatch = true
  )

  check(
    "list-unapply",
    """|object Main {
       |  val hd :: tail = List(1, 2)
       |}
       |""".stripMargin,
    """|object Main {
       |  val hd :: tail = List/*[Int<<scala/Int#>>]*/(1, 2)
       |}
       |""".stripMargin
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
       |    case hd :: tail => hd
       |  }
       |}
       |""".stripMargin
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
    hintsInPatternMatch = true
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
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  val ordered/*: String<<scala/Predef.String#>>*/ = /*augmentString<<scala/Predef.augmentString().>>(*/"acb"/*)*/.sorted/*[Char<<scala/Char#>>]*//*(using Char<<scala/math/Ordering.Char.>>)*/
           |}
           |""".stripMargin
    )
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
       |  List/*[Int<<scala/Int#>>]*/(1).collect/*[Int<<scala/Int#>>]*/ { case x => x }
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
       |  val tupleBound @ (one, two) = ("1", "2")
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
       |  val tupleBound /* comment */ @ (one/*: String<<java/lang/String#>>*/, two/*: String<<java/lang/String#>>*/) = ("1", "2")
       |}
       |""".stripMargin,
    hintsInPatternMatch = true
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
       |""".stripMargin,
    compat = Map(
      "3" ->
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
           |      checkThing1[String]/*(using instancesString<<(10:15)>>)*/
           |    }/*(using here<<(5:15)>>)*/
           |    /*StringTestOps<<(6:17)>>(*/"checkThing2"/*)*/ in {
           |      checkThing2[String]/*(using instancesString<<(10:15)>>, instancesString<<(10:15)>>)*/
           |    }/*(using here<<(5:15)>>)*/
           |  }/*(using subjectRegistrationFunction<<(3:15)>>)*/
           |
           |  /*StringTestOps<<(6:17)>>(*/"bar"/*)*/ should {
           |    /*StringTestOps<<(6:17)>>(*/"checkThing1"/*)*/ in {
           |      checkThing1[String]/*(using instancesString<<(10:15)>>)*/
           |    }/*(using here<<(5:15)>>)*/
           |  }/*(using subjectRegistrationFunction<<(3:15)>>)*/
           |
           |  def checkThing1[A](implicit ev: Eq[A])/*: Nothing<<scala/Nothing#>>*/ = ???
           |  def checkThing2[A](implicit ev: Eq[A], sem: Semigroup[A])/*: Nothing<<scala/Nothing#>>*/ = ???
           |}
           |""".stripMargin
    )
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

  check(
    "error-symbol",
    """|package example
       |case class ErrorMessage(error)
       |""".stripMargin,
    """|package example
       |case class ErrorMessage(error)
       |""".stripMargin
  )

  check(
    "context-bounds2",
    """|package example
       |object O {
       |  def test[T: Ordering](x: T) = ???
       |  test(1)
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  def test[T: Ordering](x: T)/*: Nothing<<scala/Nothing#>>*/ = ???
       |  test/*[Int<<scala/Int#>>]*/(1)/*(Int<<scala/math/Ordering.Int.>>)*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|package example
                |object O {
                |  def test[T: Ordering](x: T)/*: Nothing<<scala/Nothing#>>*/ = ???
                |  test/*[Int<<scala/Int#>>]*/(1)/*(using Int<<scala/math/Ordering.Int.>>)*/
                |}
                |""".stripMargin
    )
  )

  check(
    "context-bounds4",
    """|package example
       |object O {
       |  implicit val i: Int = 123
       |  def test[T: Ordering](x: T)(implicit v: Int) = ???
       |  test(1)
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  implicit val i: Int = 123
       |  def test[T: Ordering](x: T)(implicit v: Int)/*: Nothing<<scala/Nothing#>>*/ = ???
       |  test/*[Int<<scala/Int#>>]*/(1)/*(Int<<scala/math/Ordering.Int.>>, i<<(2:15)>>)*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|package example
                |object O {
                |  implicit val i: Int = 123
                |  def test[T: Ordering](x: T)(implicit v: Int)/*: Nothing<<scala/Nothing#>>*/ = ???
                |  test/*[Int<<scala/Int#>>]*/(1)/*(using Int<<scala/math/Ordering.Int.>>, i<<(2:15)>>)*/
                |}
                |""".stripMargin
    )
  )

  check(
    "pattern-match",
    """|package example
       |object O {
       |  val head :: tail = List(1)
       |  List(1) match {
       |    case head :: next => 
       |    case Nil =>
       |  }
       |  Option(Option(1)) match {
       |    case Some(Some(value)) => 
       |    case None =>
       |  }
       |  val (local, _) = ("", 1.0)
       |  val Some(x) = Option(1)
       |  for {
       |    x <- List((1,2))
       |    (z, y) = x
       |  } yield {
       |    x
       |  }
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  val head :: tail = List/*[Int<<scala/Int#>>]*/(1)
       |  List/*[Int<<scala/Int#>>]*/(1) match {
       |    case head :: next => 
       |    case Nil =>
       |  }
       |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(Option/*[Int<<scala/Int#>>]*/(1)) match {
       |    case Some(Some(value)) => 
       |    case None =>
       |  }
       |  val (local, _) = ("", 1.0)
       |  val Some(x) = Option/*[Int<<scala/Int#>>]*/(1)
       |  for {
       |    x <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/((1,2))
       |    (z, y) = x/*(canBuildFrom<<scala/collection/immutable/List.canBuildFrom().>>)*/
       |  } yield {
       |    x/*(canBuildFrom<<scala/collection/immutable/List.canBuildFrom().>>)*/
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.10" ->
        """|package example
           |object O {
           |  val head :: tail = List/*[Int<<scala/Int#>>]*/(1)
           |  List/*[Int<<scala/Int#>>]*/(1) match {
           |    case head :: next => 
           |    case Nil =>
           |  }
           |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(Option/*[Int<<scala/Int#>>]*/(1)) match {
           |    case Some(Some(value)) => 
           |    case None =>
           |  }
           |  val (local, _) = ("", 1.0)
           |  val Some(x) = Option/*[Int<<scala/Int#>>]*/(1)
           |  for {
           |    x <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/((1,2))
           |    (z, y) = x
           |  } yield {
           |    x
           |  }
           |}
           |""".stripMargin
    )
  )

  check(
    "pattern-match1",
    """|package example
       |object O {
       |  val head :: tail = List(1)
       |  List(1) match {
       |    case head :: next => 
       |    case Nil =>
       |  }
       |  Option(Option(1)) match {
       |    case Some(Some(value)) => 
       |    case None =>
       |  }
       |  val (local, _) = ("", 1.0)
       |  val Some(x) = Option(1)
       |  for {
       |    x <- List((1,2))
       |    (z, y) = x
       |  } yield {
       |    x
       |  }
       |}
       |""".stripMargin,
    """|package example
       |object O {
       |  val head/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(1)
       |  List/*[Int<<scala/Int#>>]*/(1) match {
       |    case head/*: Int<<scala/Int#>>*/ :: next/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => 
       |    case Nil =>
       |  }
       |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(Option/*[Int<<scala/Int#>>]*/(1)) match {
       |    case Some(Some(value/*: Int<<scala/Int#>>*/)) => 
       |    case None =>
       |  }
       |  val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
       |  val Some(x/*: Int<<scala/Int#>>*/) = Option/*[Int<<scala/Int#>>]*/(1)
       |  for {
       |    x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/((1,2))
       |    (z/*: Int<<scala/Int#>>*/, y/*: Int<<scala/Int#>>*/) = x/*(canBuildFrom<<scala/collection/immutable/List.canBuildFrom().>>)*/
       |  } yield {
       |    x/*(canBuildFrom<<scala/collection/immutable/List.canBuildFrom().>>)*/
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|package example
           |object O {
           |  val head/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(1)
           |  List/*[Int<<scala/Int#>>]*/(1) match {
           |    case head/*: Int<<scala/Int#>>*/ :: next/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => 
           |    case Nil =>
           |  }
           |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(Option/*[Int<<scala/Int#>>]*/(1)) match {
           |    case Some(Some(value/*: Int<<scala/Int#>>*/)) => 
           |    case None =>
           |  }
           |  val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
           |  val Some(x/*: Int<<scala/Int#>>*/) = Option/*[Int<<scala/Int#>>]*/(1)
           |  for {
           |    x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/((1,2))
           |    (z/*: Int<<scala/Int#>>*/, y/*: Int<<scala/Int#>>*/) = x
           |  } yield {
           |    x
           |  }
           |}
           |""".stripMargin
    ),
    hintsInPatternMatch = true
  )

  check(
    "apply-param".tag(IgnoreScala2),
    """|object Main:
       |  case class A()
       |  case class B[T]()
       |  given A = A()
       |  implicit def bar(using a: A): B[A] = B[A]()
       |  def foo(using b: B[A]): String = "aaa"
       |  val g: String = foo
       |""".stripMargin,
    """|object Main:
       |  case class A()
       |  case class B[T]()
       |  given A = A()
       |  implicit def bar(using a: A): B[A] = B[A]()
       |  def foo(using b: B[A]): String = "aaa"
       |  val g: String = foo/*(using bar<<(5:15)>>(given_A<<(4:8)>>))*/
       |""".stripMargin
  )

  check(
    "multiple-params-list",
    """|object Main {
       |  case class A()
       |  case class B()
       |  implicit val theA: A = A()
       |  def foo(b: B)(implicit a: A): String = "aaa"
       |  val g: String = foo(B())
       |}
       |""".stripMargin,
    """|object Main {
       |  case class A()
       |  case class B()
       |  implicit val theA: A = A()
       |  def foo(b: B)(implicit a: A): String = "aaa"
       |  val g: String = foo(B())/*(theA<<(4:15)>>)*/
       |}
       |""".stripMargin,
    compat = Map("3" -> """|object Main {
                           |  case class A()
                           |  case class B()
                           |  implicit val theA: A = A()
                           |  def foo(b: B)(implicit a: A): String = "aaa"
                           |  val g: String = foo(B())/*(using theA<<(4:15)>>)*/
                           |}
                           |""".stripMargin)
  )

  check(
    "implicit-chain",
    """|object Main{
       |  def hello()(implicit string: String, integer: Int, long: Long): String = {
       |    println(s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt(implicit l: Long): Int = l
       |  implicit val theLong: Long = 42
       |  hello()
       |}
       |""".stripMargin,
    """|object Main{
       |  def hello()(implicit string: String, integer: Int, long: Long): String = {
       |    println(s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt(implicit l: Long): Int = l
       |  implicit val theLong: Long = 42
       |  hello()/*(theString<<(5:15)>>(theInt<<(6:15)>>(theLong<<(7:15)>>)), theInt<<(6:15)>>(theLong<<(7:15)>>), theLong<<(7:15)>>)*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|object Main{
                |  def hello()(implicit string: String, integer: Int, long: Long): String = {
                |    println(s"Hello $string, $long, $integer!")
                |  }
                |  implicit def theString(implicit i: Int): String = i.toString
                |  implicit def theInt(implicit l: Long): Int = l
                |  implicit val theLong: Long = 42
                |  hello()/*(using theString<<(5:15)>>(theInt<<(6:15)>>(theLong<<(7:15)>>)), theInt<<(6:15)>>(theLong<<(7:15)>>), theLong<<(7:15)>>)*/
                |}
                |""".stripMargin
    )
  )

  check(
    "implicit-parameterless-def",
    """|object Main{
       |  def hello()(implicit string: String, integer: Int, long: Long): String = {
       |    println(s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt: Int = 43
       |  implicit def theLong: Long = 42
       |  hello()
       |}
       |""".stripMargin,
    """|object Main{
       |  def hello()(implicit string: String, integer: Int, long: Long): String = {
       |    println(s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt: Int = 43
       |  implicit def theLong: Long = 42
       |  hello()/*(theString<<(5:15)>>(theInt<<(6:15)>>), theInt<<(6:15)>>, theLong<<(7:15)>>)*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|object Main{
                |  def hello()(implicit string: String, integer: Int, long: Long): String = {
                |    println(s"Hello $string, $long, $integer!")
                |  }
                |  implicit def theString(implicit i: Int): String = i.toString
                |  implicit def theInt: Int = 43
                |  implicit def theLong: Long = 42
                |  hello()/*(using theString<<(5:15)>>(theInt<<(6:15)>>), theInt<<(6:15)>>, theLong<<(7:15)>>)*/
                |}
                |""".stripMargin
    )
  )

  check(
    "implicit-fn",
    """|object Main{
       |  implicit def stringLength(s: String): Int = s.length
       |  implicitly[String => Int]
       |
       |  implicit val namedStringLength: String => Long = (s: String) => s.length.toLong
       |  implicitly[String => Long]
       |}
       |""".stripMargin,
    """|object Main{
       |  implicit def stringLength(s: String): Int = s.length
       |  implicitly[String => Int]/*((s: String<<java/lang/String#>>) => stringLength<<(2:15)>>(s)))*/
       |
       |  implicit val namedStringLength: String => Long = (s: String) => s.length.toLong
       |  implicitly[String => Long]/*(namedStringLength<<(5:15)>>)*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|object Main{
                |  implicit def stringLength(s: String): Int = s.length
                |  implicitly[String => Int]
                |
                |  implicit val namedStringLength: String => Long = (s: String) => s.length.toLong
                |  implicitly[String => Long]/*(using namedStringLength<<(5:15)>>)*/
                |}
                |""".stripMargin
    )
  )

  check(
    "implicit-fn2",
    """|object Main{
       |  implicit def stringLength(s: String, i: Int): Int = s.length
       |  implicitly[(String, Int) => Int]
       |}
       |""".stripMargin,
    """|object Main{
       |  implicit def stringLength(s: String, i: Int): Int = s.length
       |  implicitly[(String, Int) => Int]/*((s: String<<java/lang/String#>>, i: Int<<scala/Int#>>) => stringLength<<(2:15)>>(s, i)))*/
       |}
       |""".stripMargin,
    compat = Map(
      "3" -> """|object Main{
                |  implicit def stringLength(s: String, i: Int): Int = s.length
                |  implicitly[(String, Int) => Int]
                |}
                |""".stripMargin
    )
  )

  check(
    "strip-margin",
    """|object Main{
       |  "".stripMargin
       |}
       |""".stripMargin,
    """|package `strip-margin`
       |object Main{
       |  /*augmentString<<scala/Predef.augmentString().>>(*/""/*)*/.stripMargin
       |}
       |""".stripMargin
  )

}
