package tests

import java.nio.file.Paths

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.mtags.MtagsEnrichments._

import munit.TestOptions

class InferExpectedTypeSuite extends BasePCSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  def check(
      name: TestOptions,
      original: String,
      expectedType: String,
      fileName: String = "A.scala"
  ): Unit = test(name) {
    presentationCompiler.restart()
    val (code, offset) = params(original.replace("@@", "CURSOR@@"), fileName)
    val offsetParams = CompilerOffsetParams(
      Paths.get(fileName).toUri(),
      code,
      offset,
      EmptyCancelToken
    )
    presentationCompiler.inferExpectedType(offsetParams).get().asScala match {
      case Some(value) => assertNoDiff(value, expectedType)
      case None => fail("Empty result.")
    }
  }

  check(
    "type-ascription",
    """|def doo = (@@ : Double)
       |""".stripMargin,
    """|Double
       |""".stripMargin
  )
// some structures

  check(
    "try",
    """|val _: Int =
       |  try {
       |    @@
       |  } catch {
       |    case _ =>
       |  }
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

  check(
    "try-catch",
    """|val _: Int =
       |  try {
       |  } catch {
       |    case _ => @@
       |  }
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

  check(
    "if-condition",
    """|val _ = if @@ then 1 else 2
       |""".stripMargin,
    """|Boolean
       |""".stripMargin
  )

  check(
    "inline-if",
    """|inline def o: Int = inline if ??? then @@ else ???
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

// pattern matching

  check(
    "pattern-match",
    """|val _ =
       |  List(1) match
       |    case @@
       |""".stripMargin,
    """|List[Int]
       |""".stripMargin
  )

  check(
    "bind",
    """|val _ =
       |  List(1) match
       |    case name @ @@
       |""".stripMargin,
    """|List[Int]
       |""".stripMargin
  )

  check(
    "alternative",
    """|val _ =
       |  List(1) match
       |    case Nil | @@
       |""".stripMargin,
    """|List[Int]
       |""".stripMargin
  )

  check(
    "unapply".ignore,
    """|val _ =
       |  List(1) match
       |    case @@ :: _ =>
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

// generic functions

  check(
    "any-generic",
    """|val _ : List[Int] = identity(@@)
       |""".stripMargin,
    """|List[Int]
       |""".stripMargin
  )

  check(
    "eq-generic",
    """|def eq[T](a: T, b: T): Boolean = ???
       |val _ = eq(1, @@)
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

  check(
    "flatmap".ignore,
    """|val _ : List[Int] = List().flatMap(_ => @@)
       |""".stripMargin,
    """|IterableOnce[Int]
       |""".stripMargin
  )

  check(
    "for-comprehension".ignore,
    """|val _ : List[Int] =
       |  for {
       |    _ <- List("a", "b")
       |  } yield @@
       |""".stripMargin,
    """|Int
       |""".stripMargin
  )

// bounds
  check(
    "any".ignore,
    """|trait Foo
       |def foo[T](a: T): Boolean = ???
       |val _ = foo(@@)
       |""".stripMargin,
    """|<: Any
       |""".stripMargin
  )

  check(
    "bounds-1".ignore,
    """|trait Foo
       |def foo[T <: Foo](a: Foo): Boolean = ???
       |val _ = foo(@@)
       |""".stripMargin,
    """|<: Foo
       |""".stripMargin
  )

  check(
    "bounds-2".ignore,
    """|trait Foo
       |def foo[T :> Foo](a: Foo): Boolean = ???
       |val _ = foo(@@)
       |""".stripMargin,
    """|:> Foo
       |""".stripMargin
  )

  check(
    "bounds-3".ignore,
    """|trait A
       |class B extends A
       |class C extends B
       |def roo[F >: C <: A](f: F) = ???
       |val kjk = roo(@@)
       |""".stripMargin,
    """|>: C <: A
       |""".stripMargin
  )

}
