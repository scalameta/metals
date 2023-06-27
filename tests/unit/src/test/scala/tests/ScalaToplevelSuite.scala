package tests

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.mtags.Mtags

import munit.TestOptions

class ScalaToplevelSuite extends BaseSuite {

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
      "_empty_/C#i.", "_empty_/D#", "_empty_/D#Da.", "_empty_/D#Db.",
      "_empty_/D#getI().", "_empty_/D#i.",
    ),
    all = true,
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
      "_empty_/D#Da.", "_empty_/D#Db.",
    ),
    all = true,
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
    all = true,
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
    ),
    all = true,
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
    all = true,
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
    all = true,
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
    all = true,
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
      "a/A.bar.",
      "a/A.foo.",
    ),
    all = true,
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
      "a/Test$package.bar.",
      "a/Test$package.foo.",
    ),
    all = true,
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
      "a/", "a/Test$package.", "a/Test$package.foo.", "a/Test$package.bar.",
      "a/Test$package.baz.",
    ),
    all = true,
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
    all = true,
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
    all = true,
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
    all = true,
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
    all = true,
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
    List("a/", "a/Planets#", "a/Planets#Earth.", "a/Planets#Mercury.",
      "a/Planets#num.", "a/Planets#Venus.", "a/NotPlanets#",
      "a/NotPlanets#Vase."),
    dialect = dialects.Scala3,
    all = true,
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
    List("a/", "a/Planets#", "a/Planets#Earth.", "a/Planets#Mercury.",
      "a/Planets#num.", "a/Planets#Venus.", "a/NotPlanets#",
      "a/NotPlanets#Vase."),
    dialect = dialects.Scala3,
    all = true,
  )

  def check(
      options: TestOptions,
      code: String,
      expected: List[String],
      all: Boolean = false,
      dialect: Dialect = dialects.Scala3,
  )(implicit location: munit.Location): Unit = {
    test(options) {
      val input = Input.VirtualFile("Test.scala", code)
      val obtained =
        if (all) {
          Mtags
            .allToplevels(input, dialect)
            .occurrences
            .map(_.symbol)
            .toList
        } else {
          Mtags.toplevels(input, dialect)
        }
      assertNoDiff(
        obtained.sorted.mkString("\n"),
        expected.sorted.mkString("\n"),
      )
    }
  }

  def assertToplevelsNoDiff(
      obtained: List[String],
      expected: List[String],
  ): Unit = {
    assertNoDiff(obtained.sorted.mkString("\n"), expected.sorted.mkString("\n"))
  }

}
