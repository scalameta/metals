package tests

import scala.meta.dialects
import scala.meta.inputs.Input
import scala.meta.internal.mtags.Mtags

import munit.TestOptions

class Scala3ToplevelSuite extends BaseSuite {

  check(
    "basic-indented",
    """|object A:
       |  def foo: Int
       |  class Z
       |
       |class B(val v: String):
       |  trait X
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
      "_empty_/D."
    )
  )

  check(
    "basic-braces",
    """|object A {
       |  def foo: Int
       |  class Z
       |}
       |class B {
       |  trait X
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
      "_empty_/D."
    )
  )

  check(
    "source-toplevel",
    """|package z
       |given abc: Int = ???
       |def foo: Int = ??? """.stripMargin,
    List(
      "z/Test$package."
    )
  )

  check(
    "extension",
    """|package z
       |extension (a: Int)
       |  def asString: String = a.toString
       |""".stripMargin,
    List(
      "z/Test$package."
    )
  )

  check(
    "type",
    """|package z
       |type X = Int | String
       |""".stripMargin,
    List(
      "z/Test$package."
    )
  )

  check(
    "given-import",
    """|package pkg.foo
       |import abc.given
       |object X
       |""".stripMargin,
    List(
      "pkg/foo/X."
    )
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
      "pkg/", "pkg/A#", "pkg/A#Z#", "pkg/B.", "pkg/B.Y#", "pkg/B.Y#L#", "pkg/C#"
    ),
    all = true
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
      "pkg/", "pkg/A#", "pkg/A#Z#", "pkg/B.", "pkg/B.Y#", "pkg/B.Y#L#"
    ),
    all = true
  )

  check(
    "package-object",
    """|package x
       |package object y:
       |  trait A
       |""".stripMargin,
    List(
      "x/y/package."
    )
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
      "org/smth/foo/B#"
    )
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
      "org/smth/foo/Z#"
    )
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
      "org/smth/foo/Z#"
    )
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
    )
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
      "_empty_/D."
    )
  )

  def check(
      options: TestOptions,
      code: String,
      expected: List[String],
      all: Boolean = false
  ): Unit = {
    test(options) {
      val input = Input.VirtualFile("Test.scala", code)
      val obtained =
        if (all) {
          Mtags
            .allToplevels(input, dialects.Scala3)
            .occurrences
            .map(_.symbol)
            .toList
        } else {
          Mtags.toplevels(input, dialect = dialects.Scala3)
        }
      assertNoDiff(
        obtained.sorted.mkString("\n"),
        expected.sorted.mkString("\n")
      )
    }
  }

  def assertToplevelsNoDiff(
      obtained: List[String],
      expected: List[String]
  ): Unit = {
    assertNoDiff(obtained.sorted.mkString("\n"), expected.sorted.mkString("\n"))
  }

}
