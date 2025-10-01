package tests.pc

import coursierapi.Dependency
import tests.BaseInlayHintsSuite

class InlayHintsSuite extends BaseInlayHintsSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val scalaBinaryVersion = createBinaryVersion(scalaVersion)
    if (scalaVersion.startsWith("2.11")) {
      Seq.empty
    } else {
      Seq(
        Dependency.of("com.chuusai", s"shapeless_$scalaBinaryVersion", "2.3.12")
      )
    }
  }

  check(
    "single-chain-same-line",
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar.bar
      |val thing2: Foo = foo.foo()
      |}
      |""".stripMargin,
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar.bar
      |val thing2: Foo = foo.foo()
      |}
      |""".stripMargin
  )

  check(
    "multi-chain-same-line",
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar.bar.bar
      |val thing2: Foo = foo.foo().foo()
      |}
      |""".stripMargin,
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar.bar.bar
      |val thing2: Foo = foo.foo().foo()
      |}
      |""".stripMargin
  )

  check(
    "single-chain-new-line",
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar
      |  .bar
      |val thing2: Foo = foo
      |  .foo()
      |}
      |""".stripMargin,
    """object Main{
      |  trait Bar {
      |   def bar: Bar
      |  }
      |
      |  trait Foo {
      |    def foo(): Foo
      |  }
      |
      |val bar: Bar = ???
      |val foo: Foo = ???
      |
      |val thing1: Bar = bar
      |  .bar
      |val thing2: Foo = foo
      |  .foo()
      |}
      |""".stripMargin
  )

  check(
    "simple-chain",
    """object Main{
      |  trait Foo {
      |   def bar: Bar
      |  }
      |
      |  trait Bar {
      |    def foo(): Foo
      |  }
      |
      |val foo: Foo = ???
      |
      |val thingy: Bar = foo
      |  .bar
      |  .foo()
      |  .bar
      |}
      |""".stripMargin,
    """object Main{
      |  trait Foo {
      |   def bar: Bar
      |  }
      |
      |  trait Bar {
      |    def foo(): Foo
      |  }
      |
      |val foo: Foo = ???
      |
      |val thingy: Bar = foo
      |  .bar/*  : Bar<<(6:8)>>*/
      |  .foo()/*: Foo<<(2:8)>>*/
      |  .bar/*  : Bar<<(6:8)>>*/
      |}
      |""".stripMargin
  )

  check(
    "long-chain",
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String*): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify
      |  .stringListify(
      |    "Hello",
      |    "World"
      |  )
      |  .stringListify(
      |    "Hello",
      |    "World"
      |  )
      |  .intify
      |  .intify
      |}
      |""".stripMargin,
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String*): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |  .stringListify(
      |    /*s = */"Hello",
      |    "World"
      |  )/*      : Foo<<(2:8)>>[String<<java/lang/String#>>]*/
      |  .stringListify(
      |    /*s = */"Hello",
      |    "World"
      |  )/*      : Foo<<(2:8)>>[String<<java/lang/String#>>]*/
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |}
      |""".stripMargin
  )

  check(
    "long-chain-same-line",
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String*): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify
      |  .stringListify(
      |    "Hello",
      |    "World"
      |  )
      |  .stringListify(
      |    "Hello",
      |    "World"
      |  )
      |  .intify.intify
      |}
      |""".stripMargin,
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String*): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify/*       : Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |  .stringListify(
      |    /*s = */"Hello",
      |    "World"
      |  )/*             : Foo<<(2:8)>>[String<<java/lang/String#>>]*/
      |  .stringListify(
      |    /*s = */"Hello",
      |    "World"
      |  )/*             : Foo<<(2:8)>>[String<<java/lang/String#>>]*/
      |  .intify.intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |}
      |""".stripMargin
  )

  check(
    "tikka masala (curried)",
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String)(s2: String): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify
      |  .stringListify(
      |    "Hello"
      |  )(
      |    "World"
      |  )
      |  .stringListify(
      |    "Hello"
      |  )(
      |    "World"
      |  )
      |  .intify
      |  .intify
      |}
      |""".stripMargin,
    """object Main{
      |  trait Foo[F] {
      |   def intify: Foo[Int]
      |   def stringListify(s: String)(s2: String): Foo[String]
      |  }
      |
      |val foo: Foo[String] = ???
      |
      |val thingy: Foo[Int] = foo
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |  .stringListify(
      |    /*s = */"Hello"
      |  )(
      |    /*s2 = */"World"
      |  )/*      : Foo<<(2:8)>>[String<<scala/Predef.String#>>]*/
      |  .stringListify(
      |    /*s = */"Hello"
      |  )(
      |    /*s2 = */"World"
      |  )/*      : Foo<<(2:8)>>[String<<scala/Predef.String#>>]*/
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |  .intify/*: Foo<<(2:8)>>[Int<<scala/Int#>>]*/
      |}
      |""".stripMargin
  )

  check(
    "for comprehension",
    """object Main{
      |trait Foo[A]{
      |  def flatMap[B](f: A => Foo[B]): Foo[B]
      |  def map[B](f: A => B): Foo[B]
      |  def bar(s: String): Foo[A]
      |}
      |val foo1: Foo[String] = ???
      |val foo2: Foo[Int] = ???
      |val result = for {
      | foo <- foo1
      | bar <- foo2
      |   .bar(s = foo)
      |   .bar(s = foo)
      |   .bar(s = foo)
      |} yield bar
      |}
      |""".stripMargin,
    """object Main{
      |trait Foo[A]{
      |  def flatMap[B](f: A => Foo[B]): Foo[B]
      |  def map[B](f: A => B): Foo[B]
      |  def bar(s: String): Foo[A]
      |}
      |val foo1: Foo[String] = ???
      |val foo2: Foo[Int] = ???
      |val result/*: Foo<<(2:6)>>[Int<<scala/Int#>>]*/ = for {
      | foo <- foo1
      | bar <- foo2
      |   .bar(s = foo)/*: Foo<<(2:6)>>[Int<<scala/Int#>>]*/
      |   .bar(s = foo)/*: Foo<<(2:6)>>[Int<<scala/Int#>>]*/
      |   .bar(s = foo)/*: Foo<<(2:6)>>[Int<<scala/Int#>>]*/
      |} yield bar
      |}
      |""".stripMargin
  )

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
       |    val x/*: Int<<scala/Int#>>*/ = addOne(/*x = */1)/*(imp<<(3:17)>>)*/
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
       |  val x/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = hello/*[List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]]*/(/*t = */List/*[Int<<scala/Int#>>]*/(/*xs = */1))
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           |  def hello[T](t: T)/*: T<<(2:12)>>*/ = t
           |  val x/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = hello/*[List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]]*/(/*t = */List/*[Int<<scala/Int#>>]*/(/*elems = */1))
           |}
           |""".stripMargin
    )
  )

  check(
    "type-params2",
    """|object Main {
       |  def hello[T](t: T) = t
       |  val x = hello(Map((1,"abc")))
       |}
       |""".stripMargin,
    """|package `type-params2`
       |object Main {
       |  def hello[T](t: T)/*: T<<(2:12)>>*/ = t
       |  val x/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]*/ = hello/*[Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]]*/(/*t = */Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/(/*elems = */(1,"abc")))
       |}
       |""".stripMargin
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
       |  val x/*: Int<<scala/Int#>>*/ = addOne(/*x = */1)/*(imp<<(3:15)>>)*/
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
       |  implicit def intToUser(x: Int): User = new User(/*name = */x.toString)
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
       |  val foo/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List[Int](/*xs = */123)
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           |  val foo/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List[Int](/*elems = */123)
           |}
           |""".stripMargin
    )
  )

  check(
    "list2",
    """|object O {
       |  def m = 1 :: List(1)
       |}
       |""".stripMargin,
    """|object O {
       |  def m/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = 1 :: List/*[Int<<scala/Int#>>]*/(/*xs = */1)
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object O {
           |  def m/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = 1 :: List/*[Int<<scala/Int#>>]*/(/*elems = */1)
           |}
           |""".stripMargin
    )
  )

  check(
    "two-param",
    """|object Main {
       |  val foo = Map((1, "abc"))
       |}
       |""".stripMargin,
    """|object Main {
       |  val foo/*: Map<<scala/collection/immutable/Map#>>[Int<<scala/Int#>>,String<<java/lang/String#>>]*/ = Map/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/(/*elems = */(1, "abc"))
       |}
       |""".stripMargin
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
       |  val foo/*: Buffer<<scala/collection/mutable/Buffer#>>[String<<scala/Predef.String#>>]*/ = List[String](/*xs = */"").toBuffer[String]
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           |  val foo/*: Buffer<<scala/collection/mutable/Buffer#>>[String<<scala/Predef.String#>>]*/ = List[String](/*elems = */"").toBuffer[String]
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
       |  val x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = Tuple2.apply/*[Int<<scala/Int#>>, Int<<scala/Int#>>]*/(/*_1 = */1, /*_2 = */2)
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
       |  val x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ = Tuple2/*[Int<<scala/Int#>>, Int<<scala/Int#>>]*/(/*_1 = */1, /*_2 = */2)
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
       |  val hd :: tail = List/*[Int<<scala/Int#>>]*/(/*xs = */1, 2)
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           |  val hd :: tail = List/*[Int<<scala/Int#>>]*/(/*elems = */1, 2)
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
       |  val x/*: Int<<scala/Int#>>*/ = List/*[Int<<scala/Int#>>]*/(/*xs = */1, 2) match {
       |    case hd :: tail => hd
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|object Main {
           |  val x/*: Int<<scala/Int#>>*/ = List/*[Int<<scala/Int#>>]*/(/*elems = */1, 2) match {
           |    case hd :: tail => hd
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
       |  val Foo(fst/*: Int<<scala/Int#>>*/, snd/*: Int<<scala/Int#>>*/) = Foo/*[Int<<scala/Int#>>]*/(/*x = */1, /*y = */2)
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
       |  List/*[Int<<scala/Int#>>]*/(/*elems = */1).collect/*[Int<<scala/Int#>>]*/ { case x => x }
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
       |  /*StringTestOps<<(6:17)>>(*/"foo"/*)*/ should {/*=> */
       |    /*StringTestOps<<(6:17)>>(*/"checkThing1"/*)*/ in {/*=> */
       |      checkThing1[String]/*(instancesString<<(10:15)>>)*/
       |    }/*(here<<(5:15)>>)*/
       |    /*StringTestOps<<(6:17)>>(*/"checkThing2"/*)*/ in {/*=> */
       |      checkThing2[String]/*(instancesString<<(10:15)>>, instancesString<<(10:15)>>)*/
       |    }/*(here<<(5:15)>>)*/
       |  }/*(subjectRegistrationFunction<<(3:15)>>)*/
       |
       |  /*StringTestOps<<(6:17)>>(*/"bar"/*)*/ should {/*=> */
       |    /*StringTestOps<<(6:17)>>(*/"checkThing1"/*)*/ in {/*=> */
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
       |    val x/*: S<<scala/collection/Set#>>[String<<java/lang/String#>>]*/ = d.map/*[String<<java/lang/String#>>, S<<scala/collection/Set#>>[String<<java/lang/String#>>]]*/(/*f = */_.toString)/*(canBuildFrom<<scala/collection/Set.canBuildFrom().>>)*/
       |    val y/*: S<<scala/collection/Set#>>[Char<<scala/Char#>>]*/ = f
       |    ???
       |  }
       |  val x/*: AB<<scala/collection/AbstractMap#>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ = test(/*d = */Set/*[Int<<scala/Int#>>]*/(/*elems = */1), /*f = */Set/*[Char<<scala/Char#>>]*/(/*elems = */'a'))
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
           |    val x/*: S<<scala/collection/Set#>>[String<<java/lang/String#>>]*/ = d.map/*[String<<java/lang/String#>>]*/(/*f = */_.toString)
           |    val y/*: S<<scala/collection/Set#>>[Char<<scala/Char#>>]*/ = f
           |    ???
           |  }
           |  val x/*: AB<<scala/collection/AbstractMap#>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ = test(/*d = */Set/*[Int<<scala/Int#>>]*/(/*elems = */1), /*f = */Set/*[Char<<scala/Char#>>]*/(/*elems = */'a'))
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
       |  test/*[Int<<scala/Int#>>]*/(/*x = */1)/*(Int<<scala/math/Ordering.Int.>>)*/
       |}
       |""".stripMargin
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
       |  test/*[Int<<scala/Int#>>]*/(/*x = */1)/*(Int<<scala/math/Ordering.Int.>>, i<<(2:15)>>)*/
       |}
       |""".stripMargin
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
       |  val head :: tail = List/*[Int<<scala/Int#>>]*/(/*xs = */1)
       |  List/*[Int<<scala/Int#>>]*/(/*xs = */1) match {
       |    case head :: next => 
       |    case Nil =>
       |  }
       |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(/*x = */Option/*[Int<<scala/Int#>>]*/(/*x = */1)) match {
       |    case Some(Some(value)) => 
       |    case None =>
       |  }
       |  val (local, _) = ("", 1.0)
       |  val Some(x) = Option/*[Int<<scala/Int#>>]*/(/*x = */1)
       |  for {
       |    x <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/(/*xs = */(1,2))
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
           |  val head :: tail = List/*[Int<<scala/Int#>>]*/(/*elems = */1)
           |  List/*[Int<<scala/Int#>>]*/(/*elems = */1) match {
           |    case head :: next => 
           |    case Nil =>
           |  }
           |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(/*x = */Option/*[Int<<scala/Int#>>]*/(/*x = */1)) match {
           |    case Some(Some(value)) => 
           |    case None =>
           |  }
           |  val (local, _) = ("", 1.0)
           |  val Some(x) = Option/*[Int<<scala/Int#>>]*/(/*x = */1)
           |  for {
           |    x <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/(/*elems = */(1,2))
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
       |  val head/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(/*xs = */1)
       |  List/*[Int<<scala/Int#>>]*/(/*xs = */1) match {
       |    case head/*: Int<<scala/Int#>>*/ :: next/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => 
       |    case Nil =>
       |  }
       |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(/*x = */Option/*[Int<<scala/Int#>>]*/(/*x = */1)) match {
       |    case Some(Some(value/*: Int<<scala/Int#>>*/)) => 
       |    case None =>
       |  }
       |  val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
       |  val Some(x/*: Int<<scala/Int#>>*/) = Option/*[Int<<scala/Int#>>]*/(/*x = */1)
       |  for {
       |    x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/(/*xs = */(1,2))
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
           |  val head/*: Int<<scala/Int#>>*/ :: tail/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ = List/*[Int<<scala/Int#>>]*/(/*elems = */1)
           |  List/*[Int<<scala/Int#>>]*/(/*elems = */1) match {
           |    case head/*: Int<<scala/Int#>>*/ :: next/*: List<<scala/collection/immutable/List#>>[Int<<scala/Int#>>]*/ => 
           |    case Nil =>
           |  }
           |  Option/*[Option<<scala/Option#>>[Int<<scala/Int#>>]]*/(/*x = */Option/*[Int<<scala/Int#>>]*/(/*x = */1)) match {
           |    case Some(Some(value/*: Int<<scala/Int#>>*/)) => 
           |    case None =>
           |  }
           |  val (local/*: String<<java/lang/String#>>*/, _) = ("", 1.0)
           |  val Some(x/*: Int<<scala/Int#>>*/) = Option/*[Int<<scala/Int#>>]*/(/*x = */1)
           |  for {
           |    x/*: (Int<<scala/Int#>>, Int<<scala/Int#>>)*/ <- List/*[(Int<<scala/Int#>>, Int<<scala/Int#>>)]*/(/*elems = */(1,2))
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
       |  val g: String = foo(/*b = */B())/*(theA<<(4:15)>>)*/
       |}
       |""".stripMargin
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
       |    println(/*x = */s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt(implicit l: Long): Int = l
       |  implicit val theLong: Long = 42
       |  hello()/*(theString<<(5:15)>>(theInt<<(6:15)>>(theLong<<(7:15)>>)), theInt<<(6:15)>>(theLong<<(7:15)>>), theLong<<(7:15)>>)*/
       |}
       |""".stripMargin
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
       |    println(/*x = */s"Hello $string, $long, $integer!")
       |  }
       |  implicit def theString(implicit i: Int): String = i.toString
       |  implicit def theInt: Int = 43
       |  implicit def theLong: Long = 42
       |  hello()/*(theString<<(5:15)>>(theInt<<(6:15)>>), theInt<<(6:15)>>, theLong<<(7:15)>>)*/
       |}
       |""".stripMargin
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
       |""".stripMargin
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
       |""".stripMargin
  )

  check(
    "implicit-fn-shapeless".tag(IgnoreScala211),
    """|object Main{
       |  import shapeless.Generic
       |  Generic[(String, Int)]
       |}
       |""".stripMargin,
    """|object Main{
       |  import shapeless.Generic
       |  Generic[(String, Int)]/*(instance<<shapeless/Generic.instance().>>((x0$1: Tuple2<<scala/Tuple2#>>) => (?term: String :: Int :: shapeless.HNil)), (x0$2: ::<<shapeless/`::`#>>) => (?term: (String, Int)))))*/
       |}
       |""".stripMargin
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

  check(
    "by-name-regular",
    """|object Main{
       |  def foo(x: => Int, y: Int, z: => Int)(w: Int, v: => Int): Unit = ()
       |  foo(1, 2, 3)(4, 5)
       |}
       |""".stripMargin,
    """|object Main{
       |  def foo(x: => Int, y: Int, z: => Int)(w: Int, v: => Int): Unit = ()
       |  foo(/*=> */1, 2, /*=> */3)(4, /*=> */5)
       |}
       |""".stripMargin,
    namedParameters = false
  )

  check(
    "by-name-regular-named",
    """|object Main{
       |  def foo(x: => Int, y: Int, z: => Int)(w: Int, v: => Int): Unit = ()
       |  foo(1, 2, 3)(4, 5)
       |}
       |""".stripMargin,
    """|object Main{
       |  def foo(x: => Int, y: Int, z: => Int)(w: Int, v: => Int): Unit = ()
       |  foo(/*x = *//*=> */1, /*y = */2, /*z = *//*=> */3)(/*w = */4, /*v = *//*=> */5)
       |}
       |""".stripMargin
  )

  check(
    "by-name-block",
    """|object Main{
       |  def Future[A](arg: => A): A = arg
       |
       |  Future(1 + 2)
       |  Future {
       |    1 + 2
       |  }
       |  Future {
       |    val x = 1
       |    val y = 2
       |    x + y
       |  }
       |  Some(Option(2).getOrElse {
       |    List(1,2)
       |      .headOption
       |  })
       |}
       |""".stripMargin,
    """|package `by-name-block`
       |object Main{
       |  def Future[A](arg: => A): A = arg
       |
       |  Future/*[Int<<scala/Int#>>]*/(/*=> */1 + 2)
       |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
       |    1 + 2
       |  }
       |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
       |    val x/*: Int<<scala/Int#>>*/ = 1
       |    val y/*: Int<<scala/Int#>>*/ = 2
       |    x + y
       |  }
       |  Some/*[Any<<scala/Any#>>]*/(Option/*[Int<<scala/Int#>>]*/(2).getOrElse/*[Any<<scala/Any#>>]*/ {/*=> */
       |    List/*[Int<<scala/Int#>>]*/(1,2)
       |      .headOption
       |  })
       |}
       |""".stripMargin,
    namedParameters = false
  )

  check(
    "closing-labels-1",
    """|object Main{
       |  def bestNumber: Int = {
       |    234
       |  }
       |}
       |""".stripMargin,
    """|object Main{
       |  def bestNumber: Int = {
       |    234
       |  }/*bestNumber*/
       |}/*Main*/
       |""".stripMargin,
    closingLabels = true
  )

  check(
    "closing-labels-weird-formatting",
    """|object Main{
       |  def bestNumber: Int = {
       |    def greatNumber: Long = {
       |      3
       |    }234}
       |}
       |""".stripMargin,
    """|object Main{
       |  def bestNumber: Int = {
       |    def greatNumber: Long = {
       |      3
       |    }/*greatNumber*/234}/*bestNumber*/
       |}/*Main*/
       |""".stripMargin,
    closingLabels = true
  )

  check(
    "closing-labels-weird-formatting",
    """|object Main{
       |  def bestNumber = {
       |    234
       |  }
       |}
       |""".stripMargin,
    """|object Main{
       |  def bestNumber/*: Int<<scala/Int#>>*/ = {
       |    234
       |  }/*bestNumber*/
       |}/*Main*/
       |""".stripMargin,
    closingLabels = true
  )

  check(
    "by-name-block-named",
    """|object Main{
       |  def Future[A](arg: => A): A = arg
       |
       |  Future(1 + 2)
       |  Future {
       |    1 + 2
       |  }
       |  Future {
       |    val x = 1
       |    val y = 2
       |    x + y
       |  }
       |  Some(Option(2).getOrElse {
       |    List(1,2)
       |      .headOption
       |  })
       |}
       |""".stripMargin,
    """|package `by-name-block-named`
       |object Main{
       |  def Future[A](arg: => A): A = arg
       |
       |  Future/*[Int<<scala/Int#>>]*/(/*arg = *//*=> */1 + 2)
       |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
       |    /*arg = */1 + 2
       |  }
       |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
       |    val x/*: Int<<scala/Int#>>*/ = 1
       |    val y/*: Int<<scala/Int#>>*/ = 2
       |    x + y
       |  }
       |  Some/*[Any<<scala/Any#>>]*/(/*value = */Option/*[Int<<scala/Int#>>]*/(/*x = */2).getOrElse/*[Any<<scala/Any#>>]*/ {/*=> */
       |    /*default = */List/*[Int<<scala/Int#>>]*/(/*xs = */1,2)
       |      .headOption
       |  })
       |}
       |""".stripMargin,
    compat = Map(
      ">=2.13.0" ->
        """|package `by-name-block-named`
           |object Main{
           |  def Future[A](arg: => A): A = arg
           |
           |  Future/*[Int<<scala/Int#>>]*/(/*arg = *//*=> */1 + 2)
           |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
           |    /*arg = */1 + 2
           |  }
           |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
           |    val x/*: Int<<scala/Int#>>*/ = 1
           |    val y/*: Int<<scala/Int#>>*/ = 2
           |    x + y
           |  }
           |  Some/*[Any<<scala/Any#>>]*/(/*value = */Option/*[Int<<scala/Int#>>]*/(/*x = */2).getOrElse/*[Any<<scala/Any#>>]*/ {/*=> */
           |    /*default = */List/*[Int<<scala/Int#>>]*/(/*elems = */1,2)
           |      .headOption
           |  })
           |}
           |""".stripMargin,
      "2.11.12" ->
        """|package `by-name-block-named`
           |object Main{
           |  def Future[A](arg: => A): A = arg
           |
           |  Future/*[Int<<scala/Int#>>]*/(/*arg = *//*=> */1 + 2)
           |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
           |    /*arg = */1 + 2
           |  }
           |  Future/*[Int<<scala/Int#>>]*/ {/*=> */
           |    val x/*: Int<<scala/Int#>>*/ = 1
           |    val y/*: Int<<scala/Int#>>*/ = 2
           |    x + y
           |  }
           |  Some/*[Any<<scala/Any#>>]*/(/*x = */Option/*[Int<<scala/Int#>>]*/(/*x = */2).getOrElse/*[Any<<scala/Any#>>]*/ {/*=> */
           |    /*default = */List/*[Int<<scala/Int#>>]*/(/*xs = */1,2)
           |      .headOption
           |  })
           |}
           |""".stripMargin
    )
  )

  check(
    "by-name-default-arguments",
    """|object Main{
       |  def foo(a: => Int = 1 + 2) = ()
       |
       |  foo()
       |  foo(4 + 2)
       |}
       |""".stripMargin,
    """|package `by-name-default-arguments`
       |object Main{
       |  def foo(a: => Int = 1 + 2)/*: Unit<<scala/Unit#>>*/ = ()
       |
       |  foo()
       |  foo(/*a = *//*=> */4 + 2)
       |}
       |""".stripMargin
  )

  check(
    "by-name-default-arguments2",
    """|object Main{
       |  def foo(a: => Int = 1 + 2) = ()
       |
       |  foo()
       |  foo(4 + 2)
       |}
       |""".stripMargin,
    """|object Main{
       |  def foo(a: => Int = 1 + 2)/*: Unit<<scala/Unit#>>*/ = ()
       |
       |  foo()
       |  foo(/*=> */4 + 2)
       |}
       |""".stripMargin,
    namedParameters = false
  )

  check(
    "multi-argument",
    """|object Main{
       |  implicit val isImplicit: Double = 3.14
       |  def fun[T](a: Int)(b: T)(implicit c: Double) = ???
       |  fun(1)("2")
       |  fun(1)(2)(3.14)
       |  fun(1)(2)
       |  fun(a = 1)(2)
       |  fun(a = 
       | 1)(2)
       |}
       |""".stripMargin,
    """|object Main{
       |  implicit val isImplicit: Double = 3.14
       |  def fun[T](a: Int)(b: T)(implicit c: Double)/*: Nothing<<scala/Nothing#>>*/ = ???
       |  fun/*[String<<java/lang/String#>>]*/(/*a = */1)(/*b = */"2")/*(isImplicit<<(2:15)>>)*/
       |  fun/*[Int<<scala/Int#>>]*/(/*a = */1)(/*b = */2)(/*c = */3.14)
       |  fun/*[Int<<scala/Int#>>]*/(/*a = */1)(/*b = */2)/*(isImplicit<<(2:15)>>)*/
       |  fun/*[Int<<scala/Int#>>]*/(a = 1)(/*b = */2)/*(isImplicit<<(2:15)>>)*/
       |  fun/*[Int<<scala/Int#>>]*/(a = 
       | 1)(/*b = */2)/*(isImplicit<<(2:15)>>)*/
       |}
       |""".stripMargin
  )

  check(
    "no-setter-arg",
    """|object Vars {
       |  var a = 2
       |  a = 2
       |  Vars.a = 3
       |}
       |""".stripMargin,
    """|object Vars {
       |  var a/*: Int<<scala/Int#>>*/ = 2
       |  a = 2
       |  Vars.a = 3
       |}
       |""".stripMargin
  )

  check(
    "java-method",
    """|import java.nio.file.Paths
       |object Vars {
       |  val a = Paths.get(".")
       |}
       |""".stripMargin,
    """|import java.nio.file.Paths
       |object Vars {
       |  val a/*: Path<<java/nio/file/Path#>>*/ = Paths.get(".")
       |}
       |""".stripMargin
  )

  check(
    "defaults".tag(IgnoreScala211),
    """|import java.nio.file.Paths
       |import java.nio.file.Path
       |object Main {
       |  case class Dependency()
       |  object Dependency{
       |    def of(
       |      organization: String,
       |      name: String,
       |      version: String,
       |    ): Dependency = ???
       |  }
       |  def downloadDependency(
       |      dep: Dependency,
       |      scalaVersion: Option[String] = None,
       |      classfiers: Seq[String] = Seq.empty,
       |      resolution: Option[Object] = None,
       |  ): List[Path] = ???
       |
       |  def downloadSemanticdbJavac: List[Path] = {
       |    downloadDependency(
       |      Dependency.of(
       |        "com.sourcegraph",
       |        "semanticdb-javac",
       |        "BuildInfo.javaSemanticdbVersion",
       |      ),
       |      None,
       |    )
       |  }
       |
       |  def downloadSemanticdbJavac2: List[Path] = {
       |    downloadDependency(
       |      Dependency.of(
       |        "com.sourcegraph",
       |        "semanticdb-javac",
       |        "BuildInfo.javaSemanticdbVersion",
       |      ),
       |      None,
       |      resolution = None
       |    )
       |  }
       |
       |}
       |""".stripMargin,
    """|package defaults
       |import java.nio.file.Paths
       |import java.nio.file.Path
       |object Main {
       |  case class Dependency()
       |  object Dependency{
       |    def of(
       |      organization: String,
       |      name: String,
       |      version: String,
       |    ): Dependency = ???
       |  }
       |  def downloadDependency(
       |      dep: Dependency,
       |      scalaVersion: Option[String] = None,
       |      classfiers: Seq[String] = Seq.empty/*[Nothing<<scala/Nothing#>>]*/,
       |      resolution: Option[Object] = None,
       |  ): List[Path] = ???
       |
       |  def downloadSemanticdbJavac: List[Path] = {
       |    downloadDependency(
       |      /*dep = */Dependency.of(
       |        /*organization = */"com.sourcegraph",
       |        /*name = */"semanticdb-javac",
       |        /*version = */"BuildInfo.javaSemanticdbVersion",
       |      ),
       |      /*scalaVersion = */None,
       |    )
       |  }
       |
       |  def downloadSemanticdbJavac2: List[Path] = {
       |    downloadDependency(
       |      Dependency.of(
       |        /*organization = */"com.sourcegraph",
       |        /*name = */"semanticdb-javac",
       |        /*version = */"BuildInfo.javaSemanticdbVersion",
       |      ),
       |      None,
       |      resolution = None
       |    )
       |  }
       |
       |}
       |""".stripMargin
  )

  check(
    "i4474",
    """|object Main extends App {
       |  class Wrapper[A](val value: A) {
       |   def this() = this(???)
       |  }
       |  def wrap[A](value: A): Wrapper[A] = new Wrapper(value)
       |
       |  val x = new Wrapper(5)
       |  val x2 = wrap(5)
       |  val x3 = new Wrapper[Int](5)
       |  val x4 = new Wrapper()
       |}
       |""".stripMargin,
    """|object Main extends App {
       |  class Wrapper[A](val value: A) {
       |   def this() = this(???)
       |  }
       |  def wrap[A](value: A): Wrapper[A] = new Wrapper/*[A<<(5:11)>>]*/(/*value = */value)
       |
       |  val x/*: Wrapper<<(2:8)>>[Int<<scala/Int#>>]*/ = new Wrapper/*[Int<<scala/Int#>>]*/(/*value = */5)
       |  val x2/*: Wrapper<<(2:8)>>[Int<<scala/Int#>>]*/ = wrap/*[Int<<scala/Int#>>]*/(/*value = */5)
       |  val x3/*: Wrapper<<(2:8)>>[Int<<scala/Int#>>]*/ = new Wrapper[Int](/*value = */5)
       |  val x4/*: Wrapper<<(2:8)>>[Nothing<<scala/Nothing#>>]*/ = new Wrapper/*[Nothing<<scala/Nothing#>>]*/()
       |}
       |""".stripMargin
  )

  check(
    "i4474-2",
    """|object Main extends App {
       |  object O {
       |    class Wrapper[A, B](val value: A, val value2: B)
       |  }
       |  val x = new O.Wrapper(5, "aaa")
       |  val x2 = new O.Wrapper[Int, String](5, "aaa")
       |}
       |""".stripMargin,
    """|object Main extends App {
       |  object O {
       |    class Wrapper[A, B](val value: A, val value2: B)
       |  }
       |  val x/*: O<<(2:9)>>.Wrapper<<(3:10)>>[Int<<scala/Int#>>,String<<java/lang/String#>>]*/ = new O.Wrapper/*[Int<<scala/Int#>>, String<<java/lang/String#>>]*/(/*value = */5, /*value2 = */"aaa")
       |  val x2/*: O<<(2:9)>>.Wrapper<<(3:10)>>[Int<<scala/Int#>>,String<<scala/Predef.String#>>]*/ = new O.Wrapper[Int, String](/*value = */5, /*value2 = */"aaa")
       |}
       |""".stripMargin
  )

}
