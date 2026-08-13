package tests

import scala.meta._
import scala.meta.internal.metals.newScalaFile.BracelessSyntax

class BracelessSyntaxSuite extends munit.FunSuite {

  private def check(
      name: String,
      code: String,
      expected: Option[Boolean],
  ): Unit =
    test(name) {
      val tree =
        dialects
          .Scala3(Input.VirtualFile(s"$name.scala", code))
          .parse[Source]
          .get
      assertEquals(BracelessSyntax.prefersBraceless(tree), expected)
    }

  check("braces-class", "class Foo { def x = 1 }", Some(false))
  check("braceless-class", "class Foo:\n  def x = 1", Some(true))
  check("object-braceless", "object O:\n  def y = 2", Some(true))
  check("trait-braces", "trait T { type A }", Some(false))
  check(
    "package-object-braceless",
    "package object p:\n  def y = 2",
    Some(true),
  )
  check("package-object-braces", "package object p { def y = 2 }", Some(false))
  check("enum-braceless", "enum E:\n  case A, B", Some(true))

  // The parent clause must not confuse the brace detection.
  check(
    "braces-with-parent",
    "class Foo extends Bar { def x = 1 }",
    Some(false),
  )
  check(
    "braceless-with-parent",
    "class Foo extends Bar:\n  def x = 1",
    Some(true),
  )
  check(
    "braceless-parent-args",
    "class Foo extends Bar[Int](1):\n  def x = 1",
    Some(true),
  )
  check("braceless-derives", "class Foo derives Eq:\n  def x = 1", Some(true))

  // A member's own braces come after the body opener, so they don't count.
  check("braceless-member-braces", "class Foo:\n  def x = { 1 }", Some(true))
  // A self-type opens the body with a brace.
  check("self-type-braces", "trait T { self: Any => def a = 1 }", Some(false))

  // A bodyless declaration gives no signal; fall through to the next one.
  check("bodyless-none", "class Foo", None)
  check("first-with-body-wins", "class A\nobject B:\n  def y = 1", Some(true))
  check("package-wrapped", "package p\n\nclass Foo:\n  def x = 1", Some(true))
  check("empty-file", "", None)
}
