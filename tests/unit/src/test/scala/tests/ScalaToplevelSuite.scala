package tests

import scala.meta.dialects

class ScalaToplevelSuite extends BaseToplevelSuite {

  check(
    "basic-indented",
    """|object A:
       |  def foo: Int
       |  class Z
       |
       |class B(val v: String):
       |  trait X
       |  def foo: Int
       |
       |trait C
       |
       |enum D:
       |  case Da, Db""".stripMargin,
    List(
      "_empty_/A.",
      "_empty_/B#",
      "_empty_/C#",
      "_empty_/D#",
    ),
  )

  check(
    "basic-indented-all",
    """|object A:
       |  def foo: Int
       |  class Z
       |
       |class B(val v: String, g: String):
       |  trait X
       |  def foo: Int
       |
       |trait C(p: String, val i: Int)
       |
       |enum D(val i : Int):
       |  def getI = i
       |  case Da extends D(1)
       |  case Db extends D(2)
       |""".stripMargin,
    List(
      "_empty_/A.", "_empty_/A.foo().", "_empty_/A.Z#", "_empty_/B#",
      "_empty_/B#X#", "_empty_/B#foo().", "_empty_/B#v.", "_empty_/C#",
      "_empty_/C#i.", "_empty_/D#", "_empty_/D.Da. -> D", "_empty_/D.Db. -> D",
      "_empty_/D#getI().", "_empty_/D#i.",
    ),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "basic-braces",
    """|object A {
       |  def foo: Int
       |  class Z
       |}
       |class B {
       |  trait X
       |  def foo: Int
       |}
       |trait C
       |
       |enum D {
       |  case Da, Db
       |}""".stripMargin,
    List(
      "_empty_/A.",
      "_empty_/B#",
      "_empty_/C#",
      "_empty_/D#",
    ),
  )

  check(
    "basic-braces-all",
    """|object A {
       |  def foo: Int
       |  class Z
       |}
       |class B {
       |  trait X
       |  def foo: Int
       |}
       |trait C
       |
       |enum D {
       |  case Da, Db
       |}""".stripMargin,
    List(
      "_empty_/A.", "_empty_/A.foo().", "_empty_/A.Z#", "_empty_/B#",
      "_empty_/B#X#", "_empty_/B#foo().", "_empty_/C#", "_empty_/D#",
      "_empty_/D.Da. -> _empty_/D#", "_empty_/D.Db. -> _empty_/D#",
    ),
    mode = All,
  )

  check(
    "source-toplevel",
    """|package z
       |given abc: Int = ???
       |def foo: Int = ??? """.stripMargin,
    List(
      "z/Test$package."
    ),
  )

  check(
    "source-toplevel-all",
    """|package z
       |given abc: Int = ???
       |def foo: Int = ??? """.stripMargin,
    List(
      "z/",
      "z/Test$package.",
      "z/Test$package.abc().",
      "z/Test$package.foo().",
    ),
    mode = All,
  )

  check(
    "extension",
    """|package z
       |extension (a: Int)
       |  def asString: String = a.toString
       |""".stripMargin,
    List(
      "z/Test$package."
    ),
  )

  check(
    "type",
    """|package z
       |type X = Int | String
       |""".stripMargin,
    List(
      "z/Test$package."
    ),
  )

  check(
    "type-all",
    """|package z
       |type X = Int | String
       |""".stripMargin,
    List(
      "z/",
      "z/Test$package.",
      "z/Test$package.X#",
      "type z/Test$package.X#",
    ),
    mode = All,
  )

  check(
    "given-import",
    """|package pkg.foo
       |import abc.given
       |object X
       |""".stripMargin,
    List(
      "pkg/foo/X."
    ),
  )

  check(
    "include-inner-classes-indented",
    """|package pkg
       |class A:
       |  trait Z
       |
       |object B:
       |  class Y:
       |    trait L
       |class C
       |""".stripMargin,
    List(
      "pkg/", "pkg/A#", "pkg/A#Z#", "pkg/B.", "pkg/B.Y#", "pkg/B.Y#L#", "pkg/C#",
    ),
    mode = All,
  )

  check(
    "include-inner-classes-braces",
    """|package pkg
       |class A {
       |  trait Z
       |}
       |
       |object B {
       |  class Y {
       |    trait L
       |  }
       |}
       |""".stripMargin,
    List(
      "pkg/", "pkg/A#", "pkg/A#Z#", "pkg/B.", "pkg/B.Y#", "pkg/B.Y#L#",
    ),
    mode = All,
  )

  check(
    "package-object",
    """|package x
       |package object y:
       |  trait A
       |""".stripMargin,
    List(
      "x/y/package."
    ),
  )

  check(
    "package",
    """|package org.smth
       |package foo
       |import blabla.{A, C}
       |
       |class A {
       |
       |}
       |
       |class B:
       |  trait Z
       |
       |""".stripMargin,
    List(
      "org/smth/foo/A#",
      "org/smth/foo/B#",
    ),
  )

  check(
    "package-with-body-indented",
    """|package org.smth.foo
       |
       |package bar:
       |  trait A
       |
       |class Z
       |""".stripMargin,
    List(
      "org/smth/foo/bar/A#",
      "org/smth/foo/Z#",
    ),
  )

  check(
    "package-with-body-braces",
    """|package org.smth.foo
       |
       |package bar {
       |  trait A
       |}
       |class Z
       |""".stripMargin,
    List(
      "org/smth/foo/bar/A#",
      "org/smth/foo/Z#",
    ),
  )

  check(
    "broken-indentation-comment",
    """|package xyz
       |
       |object Feature:
       |/*
       | * a comment
       | **/
       |  def foo: Int = ???
       |""".stripMargin,
    List(
      "xyz/Feature."
    ),
  )

  check(
    "basic-wrongly-indented",
    """|  object A:
       |    def foo: Int
       |    class Z
       |
       |class B(val v: String) {
       |  trait X
       |}
       |
       |  trait C
       |
       |enum D:
       |  case Da, Db""".stripMargin,
    List(
      "_empty_/A.",
      "_empty_/B#",
      "_empty_/C#",
      "_empty_/D#",
    ),
  )

  check(
    "nested-include-inner",
    """|package a
       |
       |object A {
       |    object B1 {
       |        case class C1(a: Int)
       |    }
       |
       |    object B2 {
       |      case class C2(a: Int)
       |    }
       |  }
       |""".stripMargin,
    List(
      "a/", "a/A.", "a/A.B1.", "a/A.B1.C1#", "a/A.B1.C1#a.", "a/A.B2.",
      "a/A.B2.C2#", "a/A.B2.C2#a.",
    ),
    mode = All,
    dialect = dialects.Scala213,
  )

  check(
    "extension-methods",
    """|package a
       |
       |object A:
       |  extension (s: String)
       |    def foo: Int = ???
       |    def bar: String =
       |      def hmm: Int = ???  // <- shouldn't be returned
       |      ???
       |
       |""".stripMargin,
    List(
      "a/",
      "a/A.",
      "a/A.bar(). EXT",
      "a/A.foo(). EXT",
    ),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "toplevel-extension",
    """|package a
       |
       |extension (s: String)
       |  def foo: Int = ???
       |  def bar: String =
       |    def hmm: Int = ???  // <- shouldn't be returned
       |    ???
       |
       |""".stripMargin,
    List(
      "a/",
      "a/Test$package.",
      "a/Test$package.bar(). EXT",
      "a/Test$package.foo(). EXT",
    ),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "inline-extension",
    """|package a
       |
       |extension (s: String) def foo: Int = ???
       |extension (i: Int) def bar: Int = ???
       |extension (l: Long)
       |  def baz: Long = ???
       |""".stripMargin,
    List(
      "a/", "a/Test$package.", "a/Test$package.foo(). EXT",
      "a/Test$package.bar(). EXT", "a/Test$package.baz(). EXT",
    ),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "unapply",
    """|package a
       |
       |object O {
       | val (s1, s2) = ???
       | var (s3, s4) = ???
       | val Some(extr) = ???
       | val CaseClass(extr1, extr2) = ???
       | val (r, SomeConstructor) = ???
       | val (p, 1) = (2, 1)
       |}
       |""".stripMargin,
    List(
      "a/", "a/O.", "a/O.s1.", "a/O.s2.", "a/O.s3().", "a/O.s4().", "a/O.extr.",
      "a/O.extr1.", "a/O.extr2.", "a/O.r.", "a/O.p.",
    ),
    mode = All,
  )

  check(
    "additional-constructor",
    """|package p
       |
       |class B() {
       |  def this(i: Int) = ???
       |}
       |""".stripMargin,
    List("p/", "p/B#"),
    mode = All,
  )

  check(
    "case-class",
    """|package p
       |
       |case class A(
       |  name: Int, isH: Boolean = false
       |) {
       |  val V = ???
       |}
       |""".stripMargin,
    List("p/", "p/A#", "p/A#name.", "p/A#isH.", "p/A#V."),
    mode = All,
  )

  check(
    "given-aliases",
    """|package a
       |given intValue: Int = 4
       |given String = "str"
       |given (using i: Int): Double = 4.0
       |given [T]: List[T] = Nil
       |given given_Char: Char = '?'
       |given `given_Float`: Float = 3.0
       |given `* *` : Long = 5
       |given [T]: List[T] =
       |  val m = 3 
       |  ???
       |given listOrd[T](using ord: List[T]): List[List[T]] = ???
       |""".stripMargin,
    List("a/", "a/Test$package.", "a/Test$package.`* *`().",
      "a/Test$package.given_Char().", "a/Test$package.given_Float().",
      "a/Test$package.intValue().", "a/Test$package.listOrd()."),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "cases-for-enum-broken-ident",
    """|package a
       |enum Planets(val num: Int){
       |num match
       |  case x => ???
       |List(1, 2, 3).collect{case someNumber => ???}
       |case Mercury extends Planets(1)
       |case Venus extends Planets(2)
       |case Earth extends Planets(3)
       |}
       |
       |enum NotPlanets{ case Vase }
       |""".stripMargin,
    List("a/", "a/Planets#", "a/Planets.Earth. -> Planets",
      "a/Planets.Mercury. -> Planets", "a/Planets#num.",
      "a/Planets.Venus. -> Planets", "a/NotPlanets#",
      "a/NotPlanets.Vase. -> a/NotPlanets#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "cases-for-enum-ident",
    """|package a
       |enum Planets(val num: Int):
       |  num match
       |    case 1 =>
       |    case other =>
       |  List(1, 2, 3).collect:
       |    case someNumber =>
       |  case Mercury extends Planets(1)
       |  case Venus extends Planets(2)
       |  case Earth extends Planets(3)
       |
       |enum NotPlanets:
       |  case Vase
       |""".stripMargin,
    List("a/", "a/Planets#", "a/Planets.Earth. -> Planets",
      "a/Planets.Mercury. -> Planets", "a/Planets#num.",
      "a/Planets.Venus. -> Planets", "a/NotPlanets#",
      "a/NotPlanets.Vase. -> a/NotPlanets#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "cases-for-enum-class",
    """|package a
       |enum Planets(val num: Int):
       |  case Mercury() extends Planets(1)
       |  case Venus[A]() extends Planets(2)
       |  case Earth(val v: Int) extends Planets(3)
       |  def mmm() = ???
       |
       |enum NotPlanets:
       |  case Vase
       |""".stripMargin,
    List("a/", "a/Planets#", "a/Planets#mmm().", "a/Planets.Earth# -> Planets",
      "a/Planets.Earth#v.", "a/Planets.Mercury# -> Planets", "a/Planets#num.",
      "a/Planets.Venus# -> Planets", "a/NotPlanets#",
      "a/NotPlanets.Vase. -> a/NotPlanets#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "i5500",
    """|package a
       |abstract class TypeProxy extends Type {
       |  def superType(using Context): Type = underlying match {
       |    case TypeBounds(_, hi) => hi
       |    case st => st
       |  }
       |}
       |""".stripMargin,
    List("a/", "a/TypeProxy# -> Type"),
    dialect = dialects.Scala3,
    mode = ToplevelWithInner,
  )

  check(
    "class-in-def",
    """|package a
       |def ee =
       |  class AClass
       |  1
       |""".stripMargin,
    List("a/", "a/Test$package."),
    dialect = dialects.Scala3,
    mode = ToplevelWithInner,
  )

  check(
    "if-in-class",
    """|class A(i : Int):
       |  if i > 0
       |  then
       |    def a(j: Int) = j
       |    a(i)
       |  else ???
       |""".stripMargin,
    List("_empty_/A#"),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "for-loop",
    """|class A:
       |  for
       |    _ <- Some(1)
       |    _ <- 
       |      def a(i: Int) = if i > 0 then Some(i) else None
       |      a(3)
       |  yield ()
       |""".stripMargin,
    List("_empty_/A#"),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "i6643",
    """|package a
       |class A {
       |  def foo(): Int
       |         = {
       |  }
       |  class B
       |}
       |""".stripMargin,
    List("a/", "a/A#", "a/A#foo().", "a/A#B#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "i6643-2",
    """|package a
       |class A {
       |  def foo
       |    (a: Int)
       |    (l: Int) = {
       |        ???
       |  }
       |  class B
       |}
       |""".stripMargin,
    List("a/", "a/A#", "a/A#foo().", "a/A#B#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "companion-to-type",
    """|package s
       |type Cow = Long
       |
       |object Cow:
       |  def apply(m: Long): Cow = m
       |
       |""".stripMargin,
    // For `s/Cow.` and `s/Cow.apply().` the corresponding symbols created by the compiler
    // will be respectively `s/Test$package/Cow.` and `s/Test$package/Cow.apply().`.
    //
    // It is easier to work around this inconstancy in `SemanticdbSymbols.inverseSemanticdbSymbol`
    // than to change symbols emitted by `ScalaTopLevelMtags`,
    // since the object could be placed before type definition.
    List("s/", "s/Test$package.", "s/Test$package.Cow# -> Long", "s/Cow.",
      "s/Cow.apply().", "type s/Test$package.Cow#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "overridden",
    """|package a
       |case class A[T](v: Int)(using Context) extends B[Int](2) with C:
       |  object O extends H
       |class M(ctx: Context) extends W(1)(ctx)
       |""".stripMargin,
    List("a/", "a/A# -> B, C", "a/A#v.", "a/A#O. -> H", "a/M# -> W"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "overridden2",
    """|package a
       |class A extends b.B
       |""".stripMargin,
    List("a/", "a/A# -> B"),
    mode = All,
  )

  check(
    "overridden3",
    """|package a
       |class A extends B, C
       |""".stripMargin,
    List("a/", "a/A# -> B, C"),
    mode = All,
  )

  check(
    "overridden-type-alias",
    """|package a
       |object O {
       |  type A[X] = Set[X]
       |  type W[X] = mutable.Set[X]
       |  type H = [X] =>> List[X]
       |  type R = Set[Int] { def a: Int }
       |  opaque type L <: mutable.List[Int] = mutable.List[Int]
       |  type Elem[X] = X match
       |      case String => Char
       |      case Array[t] => t
       |      case Iterable[t] => t
       |  class Bar
       |}
       |class Foo
       |""".stripMargin,
    List("a/", "a/Foo#", "a/O.", "a/O.A# -> Set", "a/O.H# -> List",
      "a/O.W# -> Set", "a/O.R# -> Set", "a/O.L# -> List", "a/O.Bar#",
      "a/O.Elem#"),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "overridden-type-alias-2",
    """|package a
       |type A[X] = Set[X]
       |type W[X] = mutable.Set[X]
       |type Elem[X] = X match
       |  case String => Char
       |  case Array[t] => t
       |  case Iterable[t] =>
       |    class A()
       |    a
       |class Bar
       |""".stripMargin,
    List("a/", "a/Bar#", "a/Test$package.", "type a/Test$package.A#",
      "type a/Test$package.Elem#", "type a/Test$package.W#"),
    dialect = dialects.Scala3,
    mode = ToplevelWithInner,
  )

  check(
    "refined-type",
    """|package a
       |object O {
       |  trait Foo {
       |    type T
       |  }
       |
       |  implicit class A(val foo: Foo { type T = Int }) {
       |    def get: Int = 1
       |  }
       |}
       |""".stripMargin,
    List(
      "a/", "a/O.", "a/O.A#", "a/O.A#foo. EXT", "a/O.A#get(). EXT", "a/O.Foo#",
      "a/O.Foo#T#",
    ),
    mode = All,
  )

  check(
    "implicit-class-with-val",
    """|package a
       |object Foo {
       |  implicit class IntOps(private val i: Int) extends AnyVal {
       |    def inc: Int = i + 1
       |  }
       |}
       |""".stripMargin,
    List(
      "a/", "a/Foo.", "a/Foo.IntOps# -> AnyVal", "a/Foo.IntOps#i. EXT",
      "a/Foo.IntOps#inc(). EXT",
    ),
    mode = All,
  )

  check(
    "object-type-alias",
    """|object A
       |object O {
       |  type T = A.type
       |  type R = Int
       |}
       |""".stripMargin,
    List(
      "_empty_/A.",
      "_empty_/O.",
      "_empty_/O.R# -> Int",
      "_empty_/O.T# -> A",
    ),
    mode = All,
  )

  check(
    "value-definition",
    """|package a
       |
       |object A:
       |  val a = null.asInstanceOf[User]
       |  val b = 1
       |end A
       |""".stripMargin,
    List("a/", "a/A.", "a/A.a.", "a/A.b."),
    dialect = dialects.Scala3,
    mode = All,
  )

  check(
    "i6712",
    """"
      |package a
      |
      |trait Strict
      |
      |object FormData {
      |  def apply() = Strict {
      |    _ => true
      |  }
      |  case class Strict(i: Unit => Boolean)
      |}
      |
      |""".stripMargin,
    List("a/", "a/FormData.", "a/Strict#", "a/FormData.Strict#"),
    mode = ToplevelWithInner,
  )

  check(
    "i3808",
    """|package a
       |trait B
       |trait A {
       | self: B =>
       |
       |}
       |""".stripMargin,
    List("a/", "a/A# -> B", "a/B#"),
    mode = All,
  )

  check(
    "package-members",
    """|package a
       |package object b{
       |  type A = Int
       |  type B = String
       |}
       |""".stripMargin,
    List(
      "a/",
      "a/b/package.",
      "type a/b/package.A#",
      "type a/b/package.B#",
    ),
    mode = ToplevelWithInner,
  )

  check(
    "package-members-scala3",
    """|package a
       |type A = Int
       |opaque type B = String
       |""".stripMargin,
    List(
      "a/",
      "a/Test$package.",
      "type a/Test$package.A#",
      "type a/Test$package.B#",
    ),
    mode = ToplevelWithInner,
  )

  check(
    "i3808-2",
    """|package a
       |trait B
       |trait A:
       | this: B =>
       |""".stripMargin,
    List("a/", "a/A# -> B", "a/B#"),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "i3808-3",
    """|package a
       |trait B[T]
       |trait C
       |trait A:
       | this: B[Int] & C =>
       |""".stripMargin,
    List("a/", "a/A# -> C, B", "a/B#", "a/C#"),
    mode = All,
    dialect = dialects.Scala3,
  )

  check(
    "i3808-4",
    """|package a
       |trait B[T]
       |trait C
       |trait A:
       | this: B[Int] | C =>
       |""".stripMargin,
    List("a/", "a/A#", "a/B#", "a/C#"),
    mode = All,
    dialect = dialects.Scala3,
  )
}
