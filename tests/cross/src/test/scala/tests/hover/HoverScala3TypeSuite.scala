package tests.hover

import tests.pc.BaseHoverSuite

class HoverScala3TypeSuite extends BaseHoverSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala2)

  check(
    "union",
    """
      |import java.nio.file._
      |case class Foo(x: Int)
      |case class Bar[T](x: T)
      |object a {
      |  val name: Foo | Bar[Files] = Foo(1)
      |  <<na@@me>>
      |}
      |""".stripMargin,
    """|val name: Foo | Bar[Files]
       |""".stripMargin.hover
  )

  check(
    "intersection",
    """
      |import java.nio.file._
      |
      |trait Resettable:
      |  def reset(): Unit
      |
      |trait Growable[T]:
      |  def add(t: T): Unit
      |
      |def f(arg: Resettable & Growable[Files]) = {
      |  <<ar@@g.reset()>>
      |}
      |""".stripMargin,
    """|arg: Resettable & Growable[Files]
       |""".stripMargin.hover
  )

  // We should produce a shorter type but:
  // https://github.com/lampepfl/dotty/issues/11683
  check(
    "enums",
    """|
       |object SimpleEnum:
       |  enum Color:
       |   case <<Re@@d>>, Green, Blue
       |
       |""".stripMargin,
    """|case Red: Red
       |""".stripMargin.hover,
    compat = Map(
      ">=3.1.3-RC1-bin-20220301-fae7c09-NIGHTLY" ->
        """|case Red: Color
           |""".stripMargin.hover
    )
  )

  check(
    "enums2",
    """|
       |object SimpleEnum:
       |  enum <<Col@@or>>:
       |   case Red, Green, Blue
       |
       |""".stripMargin,
    """|enum Color: enums2.SimpleEnum
       |""".stripMargin.hover
  )

  check(
    "enums-outermost",
    """|enum Color:
       |  case Red
       |  case <<Bl@@ue>>
       |  case Cyan
       |""".stripMargin,
    "",
    compat = Map(
      ">=3.1.3-RC1-bin-20220301-fae7c09-NIGHTLY" ->
        """|case Blue: Color
           |""".stripMargin.hover
    )
  )

  check(
    "enums3",
    """|
       |object SimpleEnum:
       |  enum Color:
       |    case Red, Green, Blue
       |  val color = <<Col@@or>>.Red
       |
       |""".stripMargin,
    """|enum Color: enums3.SimpleEnum
       |""".stripMargin.hover
  )

  check(
    "enum-params",
    """|
       |object SimpleEnum:
       |  enum Color:
       |    case <<Gr@@een>> extends Color(2)
       |    case Red extends Color(1)
       |    case Blue extends Color(3)
       |
       |
       |""".stripMargin,
    """|case Green: Color
       |""".stripMargin.hover
  )

  check(
    "extension-methods",
    """|
       |object Foo:
       |    extension (s: String)
       |        def double = s + s
       |        def double2 = s + s        
       |    end extension
       |    "".<<doub@@le2>>
       |end Foo
       |""".stripMargin,
    "extension (s: String) def double2: String".hover
  )

  /* Currently there is no way to differentiate between
   * trailing using params in extension parameter and the
   * starting using params for the actual method.
   * As user can actually supply params to them by hand when
   * invoking the extension method, we always show them next to the
   * method itself.
   * https://github.com/lampepfl/dotty/issues/13123
   */
  check(
    "extension-methods-complex",
    """|class A
       |class B
       |class C
       |object Foo:
       |    extension [T](using A)(s: T)(using B)
       |        def double[G <: Int](using C)(times: G) = (s.toString + s.toString) * times
       |    end extension
       |    given A with {}
       |    given B with {}
       |    given C with {}
       |    "".<<doub@@le(1)>>
       |end Foo
       |""".stripMargin,
    "extension [T](using A)(s: T) def double(using B)[G <: Int](using C)(times: G): String".hover
  )

  check(
    "extension-methods-complex-binary",
    """|class A
       |class B
       |class C
       |
       |object Foo:
       |    extension [T](using A)(main: T)(using B)
       |      def %:[R](res: R)(using C): R = ???
       |    given A with {}
       |    given B with {}
       |    given C with {}
       |    val c = C()
       |    "" <<%@@:>> 11
       |end Foo
       |""".stripMargin,
    """|Int
       |extension [T](using A)(main: T) def %:[R](res: R)(using B)(using C): R""".stripMargin.hover
  )

  check(
    "using",
    """
      |object a {
      |  def apply[T](a: T)(using Int): T = ???
      |  implicit val ev = 1
      |  <<ap@@ply("test")>>
      |}
      |""".stripMargin,
    """|String
       |def apply[T](a: T)(using Int): T
       |""".stripMargin.hover
  )

  check(
    "toplevel-left",
    """|def foo = <<L@@eft>>("")
       |""".stripMargin,
    """|Left[String, Nothing]
       |def apply[A, B](value: A): Left[A, B]
       |""".stripMargin.hover
  )

  check(
    "selectable",
    """|trait Sel extends Selectable:
       |  def selectDynamic(name: String): Any = ???
       |  def applyDynamic(name: String)(args: Any*): Any = ???
       |val sel = (new Sel {}).asInstanceOf[Sel { def foo2: Int}]
       |val foo2 = sel.fo@@o2
       |""".stripMargin,
    """|def foo2: Int
       |""".stripMargin.hover
  )

  check(
    "selectable2",
    """|trait Sel extends Selectable:
       |  def selectDynamic(name: String): Any = ???
       |  def applyDynamic(name: String)(args: Any*): Any = ???
       |val sel = (new Sel {}).asInstanceOf[Sel { def bar2(x: Int): Int }]
       |val bar2 = sel.ba@@r2(3)
       |""".stripMargin,
    """|def bar2(x: Int): Int
       |""".stripMargin.hover
  )
  check(
    "selectable-full",
    """|trait Sel extends Selectable:
       |  def foo1: Int = ???
       |  def bar1(x: Int): Int = ???
       |  def selectDynamic(name: String): Any = ???
       |  def applyDynamic(name: String)(args: Any*): Any = ???
       |val sel = (new Sel {}).asInstanceOf[Sel { def foo2: Int; def bar2(x: Int): Int }]
       |val bar2 = sel.fo@@o2
       |""".stripMargin,
    """|def foo2: Int
       |""".stripMargin.hover
  )

  check(
    "structural-types",
    """|
       |import reflect.Selectable.reflectiveSelectable
       |
       |object StructuralTypes:
       |   type User = {
       |   def name: String
       |   def age: Int
       |   }
       |
       |   val user = null.asInstanceOf[User]
       |   user.name
       |   user.ag@@e
       |
       |   val V: Object {
       |   def scalameta: String
       |   } = new:
       |   def scalameta = "4.0"
       |   V.scalameta
       |end StructuralTypes
       |""".stripMargin,
    """|def age: Int
       |""".stripMargin.hover
  )

  check(
    "structural-types1",
    """|
       |import reflect.Selectable.reflectiveSelectable
       |
       |object StructuralTypes:
       |   type User = {
       |   def name: String
       |   def age: Int
       |   }
       |
       |   val user = null.asInstanceOf[User]
       |   user.name
       |   user.age
       |
       |   val V: Object {
       |   def scalameta: String
       |   } = new:
       |   def scalameta = "4.0"
       |   V.scala@@meta
       |end StructuralTypes
       |""".stripMargin,
    """|def scalameta: String
    """.stripMargin.hover
  )

  check(
    "macro",
    """|
       |import scala.quoted.*
       |
       |def myMacroImpl(using Quotes) =
       |  import quotes.reflect.Ident
       |  def foo = ??? match
       |    case x: I@@dent => x
       |
       |  def bar: Ident = foo
       |
       |  ???
       |
       |""".stripMargin,
    """|type Ident: Ident
       |""".stripMargin.hover
  )

  check(
    "macro2",
    """|
       |
       |import scala.quoted.*
       |
       |def myMacroImpl(using Quotes) =
       |  import quotes.reflect.Ident
       |  def foo = ??? match
       |    case x: Ident => x
       |
       |  def bar: Ide@@nt = foo
       |
       |  ???
       |
       |""".stripMargin,
    """|type Ident: Ident
       |""".stripMargin.hover
  )

  check(
    "nested-selectable",
    """|trait Sel extends Selectable:
       |  def selectDynamic(name: String): Any = ???
       |val sel = (new Sel {}).asInstanceOf[Sel { val foo: Sel { def bar: Int } }]
       |val bar = sel.foo.ba@@r
       |""".stripMargin,
    """|def bar: Int
       |""".stripMargin.hover
  )

  check(
    "nested-selectable2",
    """|class SimpleSelectable(key : String, value: Any) extends Selectable:
       |  def selectDynamic(name: String): Any =
       |    if(name == key) value else ???
       |
       |type Node[T] = SimpleSelectable { val child: T }
       |
       |val leaf = SimpleSelectable("child", ()).asInstanceOf[Node[Unit]]
       |val node = SimpleSelectable("child", leaf).asInstanceOf[Node[Node[Unit]]]
       |
       |val k = node.child.ch@@ild
       |""".stripMargin,
    """|val child: Unit
       |""".stripMargin.hover
  )

  check(
    "very-nested-selectable",
    """|trait Sel extends Selectable:
       |  def selectDynamic(name: String): Any = ???
       |val sel = (new Sel {}).asInstanceOf[Sel { val foo: Sel { val bar: Sel { val ddd: Int } } }]
       |val bar = sel.foo.bar.dd@@d
       |""".stripMargin,
    """|val ddd: Int
       |""".stripMargin.hover
  )

  check(
    "i5630",
    """|class MyIntOut(val value: Int)
       |object MyIntOut:
       |  extension (i: MyIntOut) def uneven = i.value % 2 == 1
       |
       |val a = MyIntOut(1).un@@even
       |""".stripMargin,
    """|extension (i: MyIntOut) def uneven: Boolean
       |""".stripMargin.hover
  )

  check(
    "i5921",
    """|object Logarithms:
       |  trait Logarithm
       |  extension [K](vmap: Logarithm)
       |    def multiply(k: Logarithm): Logarithm = ???
       |
       |object Test:
       |  val in: Logarithms.Logarithm = ???
       |  in.multi@@ply(in)
       |""".stripMargin,
    "extension [K](vmap: Logarithm) def multiply(k: Logarithm): Logarithm".hover
  )

  check(
    "i5976",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def noTypeArg: A
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |
       |  usage[Option[Int]](_.typeArg[Some[Int]].value.noTyp@@eArg.typeArg[Int])
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |Int
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def noTypeArg: A
       |```
       |""".stripMargin
  )

  check(
    "i5976-1",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def noTypeArg: A
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |
       |  usage[Option[Int]](_.type@@Arg[Some[Int]].value.noTypeArg.typeArg[Int])
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |Some[Int]
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def typeArg[B <: A]: B
       |```
       |""".stripMargin
  )

  check(
    "i5977",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def inferredTypeArg[C](value: C): C
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |  
       |  usage[Option[Int]](_.infer@@redTypeArg("str"))
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |String
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def inferredTypeArg[C](value: C): C
       |```
       |""".stripMargin
  )

  check(
    "i5977-1",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def inferredTypeArg[C](value: C): C
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |  
       |  usage[Option[Int]](_.infer@@redTypeArg[String]("str"))
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |String
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def inferredTypeArg[C](value: C): C
       |```
       |""".stripMargin
  )

  check(
    "i5977-2",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def inferredTypeArg[C](value: C): C
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |  
       |  usage[Option[Int]](_.typeArg[Some[Int]].value.infer@@redTypeArg("str"))
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |String
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def inferredTypeArg[C](value: C): C
       |```
       |""".stripMargin
  )

  check(
    "i5977-3",
    """|sealed trait ExtensionProvider {
       |  extension [A] (self: A) {
       |    def typeArg[B <: A]: B
       |    def inferredTypeArg[C](value: C): C
       |  }
       |}
       |
       |object Repro {
       |  def usage[A](f: ExtensionProvider ?=> A => Any): Any = ???
       |  
       |  usage[Option[Int]](_.typeArg[Some[Int]].value.infer@@redTypeArg[String]("str"))
       |}
       |""".stripMargin,
    """|**Expression type**:
       |```scala
       |String
       |```
       |**Symbol signature**:
       |```scala
       |extension [A](self: A) def inferredTypeArg[C](value: C): C
       |```
       |""".stripMargin
  )
}
