package tests.pc

import tests.BaseCompletionSuite
import funsuite.Location

class PrettyPrintSuite extends BaseCompletionSuite {

  def checkSignature(
      name: String,
      original: String,
      expected: String,
      compat: Map[String, String] = Map.empty
  )(implicit loc: Location): Unit = {
    val signature = original.replaceAllLiterally("@@", "")
    val completion = original.replaceFirst("@@.*", "@@")
    checkEditLine(
      name,
      s"""package ${scala.meta.Term.Name(name)}
         |abstract class Abstract {
         |  ${signature}
         |}
         |class Main extends Abstract {
         |  ___
         |}
      """.stripMargin,
      completion,
      getExpected(expected, compat) + " = ${0:???}"
    )
  }

  checkSignature(
    "this-type",
    "def foo@@(): this.type",
    // NOTE(olafur): this expected output is undesirable, we widen the type too aggressively.
    // It would be nice to fix this if possible.
    "def foo(): Main"
  )

  checkSignature(
    "single-type",
    "def foo@@(x: Int): x.type",
    // NOTE(olafur): this expected output is undesirable, I couldn't get this working because
    // `sym.info` on the supermethod has an error result type.
    "def foo(x: Int): Any",
    compat = Map(
      "2.13" -> "def foo(x: Int): x.type"
    )
  )

  checkSignature(
    "structural",
    "def foo@@(duck: {def quack(): Unit}): Unit",
    // NOTE(olafur): the ideal output would be without the redunant `AnyRef` but we need to implement
    // our own pretty-printer to fix that.
    "def foo(duck: AnyRef{def quack(): Unit}): Unit"
  )

  checkSignature(
    "int-literal",
    "def foo@@ = 42",
    "override def foo: Int"
  )

  checkSignature(
    "string-literal",
    "def foo@@ = \"foo\"",
    "override def foo: String"
  )

  checkSignature(
    "int-val-literal",
    "val foo@@ = 42",
    "override val foo: Int"
  )

  checkSignature(
    "int-val-literal",
    "def foo@@: Int with String",
    "def foo: Int with String"
  )

  checkSignature(
    "annotated",
    "def foo@@: Int @deprecated(\"\", \"\") ",
    "def foo: Int"
  )

  checkSignature(
    "by-name",
    "def foo@@(x: => Int): Unit",
    "def foo(x: => Int): Unit"
  )

  checkSignature(
    "vararg",
    "def foo@@(x: Int*): Unit",
    "def foo(x: Int*): Unit"
  )

  checkSignature(
    "forSome",
    "def foo@@: Option[T] forSome { type T }",
    "def foo: Option[_]"
  )

  checkSignature(
    "forSome2",
    "def foo@@: Option[T] forSome { type T <: Int }",
    "def foo: Option[_ <: Int]"
  )

  checkSignature(
    "forSome3",
    "def foo@@: Map[T, T] forSome { type T <: Int }",
    "def foo: Map[T,T] forSome { type T <: Int }"
  )

  checkSignature(
    "function",
    "def foo@@(fn: Int => String): Unit",
    "def foo(fn: Int => String): Unit"
  )

  checkSignature(
    "tuple",
    "def foo@@(tuple: (Int, String)): Unit",
    "def foo(tuple: (Int, String)): Unit"
  )

  checkSignature(
    "upper-bound",
    "def foo@@[T <: CharSequence](arg: T): T",
    "def foo[T <: CharSequence](arg: T): T"
  )

  checkSignature(
    "lower-bound",
    "def foo@@[T >: CharSequence](arg: T): T",
    "def foo[T >: CharSequence](arg: T): T"
  )

  checkSignature(
    "upper-lower-bound",
    "def foo@@[T >: String <: CharSequence](arg: T): T",
    "def foo[T >: String <: CharSequence](arg: T): T"
  )

  checkSignature(
    "context-bound",
    "def foo@@[T:Ordering](arg: T): T",
    "def foo[T: Ordering](arg: T): T"
  )

  checkSignature(
    "view-bound",
    "def foo@@[T <% String](arg: T): T",
    // View bounds are not resugared into `<% String` because they're considered a bad practice.
    "def foo[T](arg: T)(implicit evidence$1: T => String): T"
  )
}
