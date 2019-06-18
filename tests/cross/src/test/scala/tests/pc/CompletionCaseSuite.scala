package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig
import tests.BaseCompletionSuite

object CompletionCaseSuite extends BaseCompletionSuite {

  def paramHint = Some("param-hint")

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      _parameterHintsCommand = paramHint
    )

  override val compatProcess: Map[String, String => String] = Map(
    "2.11" -> { s: String =>
      // The standard library renamed fields of Some/Left/Right for 2.12.0.
      s.replaceAllLiterally("Some(value)", "Some(x)")
        .replaceAllLiterally("Left(value)", "Left(a)")
        .replaceAllLiterally("Right(value)", "Right(b)")
    }
  )

  check(
    "empty",
    """
      |object A {
      |  Option(1) match {
      |    @@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "typed",
    """package pkg
      |trait Animal
      |case class Bird(name: String) extends Animal
      |class Cat extends Animal
      |class Dog extends Animal
      |object Elephant extends Animal
      |class HasFeet[A, B](e: T, f: B) extends Animal
      |class HasMouth[T](e: T) extends Animal
      |case class HasWings[T](e: T) extends Animal
      |case object Seal extends Animal
      |object A {
      |  val animal: Animal = ???
      |  animal match {
      |    @@
      |  }
      |}""".stripMargin,
    """|case Bird(name) => pkg
       |case _: Cat => pkg
       |case _: Dog => pkg
       |case Elephant => pkg
       |case _: HasFeet[_, _] => pkg
       |case _: HasMouth[_] => pkg
       |case HasWings(e) => pkg
       |case Seal => pkg
       |""".stripMargin
  )

  check(
    "case",
    """package kase
      |object A {
      |  Option(1) match {
      |    cas@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "trailing",
    """
      |object A {
      |  Option(1) match {
      |    case Some(a) =>
      |    cas@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "trailing-block",
    """
      |object A {
      |  Option(1) match {
      |    case Some(a) => println(a)
      |    cas@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "either",
    """
      |object A {
      |  (null: Either[Int, String]) match {
      |    case@@
      |  }
      |}""".stripMargin,
    """|case Left(value) => scala.util
       |case Right(value) => scala.util
       |""".stripMargin
  )

  check(
    "sealed-import",
    """
      |object A {
      |  val t: scala.util.Try[Int] = ???
      |  t match {
      |    case@@
      |  }
      |}""".stripMargin,
    """|case Failure(exception) => scala.util
       |case Success(value) => scala.util
       |""".stripMargin
  )

  check(
    "sealed-two",
    """
      |object Outer {
      |  sealed trait Adt
      |  sealed trait AdtTwo extends Adt
      |  case class Cls(a: Int, b: String) extends AdtTwo
      |}
      |object A {
      |  val t: Outer.Adt = ???
      |  t match {
      |    case@@
      |  }
      |}""".stripMargin,
    // Assert we don't include AdtTwo in the results.
    """|case Cls(a, b) => `sealed-two`.Outer
       |""".stripMargin,
    compat = Map(
      // known-direct subclasses doesn't work well in 2.11 apparently.
      "2.11" -> ""
    )
  )

  check(
    "sealed-conflict",
    """
      |object A {
      |  val e: Either[Int, String] = ???
      |  type Left = String
      |  e match {
      |    case@@
      |  }
      |}""".stripMargin,
    """|case scala.util.Left(value) =>
       |case Right(value) => scala.util
       |""".stripMargin
  )

  checkEdit(
    "sealed-import-edit",
    """
      |object A {
      |  val t: scala.util.Try[Int] = ???
      |  t match {
      |    case@@
      |  }
      |}""".stripMargin,
    """
      |import scala.util.Failure
      |
      |object A {
      |  val t: scala.util.Try[Int] = ???
      |  t match {
      |    case Failure(exception) => $0
      |  }
      |}""".stripMargin,
    filter = _.contains("Failure")
  )

  checkEdit(
    "local-case",
    """
      |import scala.util.Try
      |import scala.util.Success
      |object A {
      |  Try(1) match {
      |    case Success(x) =>
      |      println(x)
      |    case@@
      |  }
      |}""".stripMargin,
    """
      |import scala.util.Try
      |import scala.util.Success
      |import scala.util.Failure
      |object A {
      |  Try(1) match {
      |    case Success(x) =>
      |      println(x)
      |    case Failure(exception) => $0
      |  }
      |}""".stripMargin,
    filter = _.contains("Failure")
  )

  check(
    "apply-type",
    """
      |object A {
      |  List(Option(1)).foreach[Int] {
      |    case None => 1
      |    case@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "lambda-function1",
    """
      |object A {
      |  List(Option(1)).foreach {
      |    case None =>
      |    case@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "lambda-function2",
    """
      |object A {
      |  List(1).foldLeft(0) {
      |    case (1, 2) =>
      |    case@@
      |  }
      |}""".stripMargin,
    """|case (Int, Int) => scala
       |""".stripMargin
  )

  check(
    "lambda",
    """
      |object A {
      |  List(Option(1)).foreach {
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "lambda-case",
    """
      |object A {
      |  List(Option(1)).foreach {
      |    case None =>
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "lambda-case-block",
    """
      |object A {
      |  List(Option(1)).foreach {
      |    case None => println(1)
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "lambda-curry",
    """
      |object A {
      |  List(Option(1)).map {
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "partial",
    """
      |object A {
      |  List(Option(1)).collect {
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "partial-case",
    """
      |object A {
      |  List(Option(1)).collect {
      |    case None =>
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "partial-case-block",
    """
      |object A {
      |  List(Option(1)).collect {
      |    case None => println(1)
      |    ca@@
      |  }
      |}""".stripMargin,
    """|case None => scala
       |case Some(value) => scala
       |""".stripMargin
  )

  check(
    "infix",
    """
      |object A {
      |  List(1) match {
      |    cas@@
      |  }
      |}""".stripMargin,
    """|case Nil => scala.collection.immutable
       |case head :: tl => scala.collection.immutable
       |""".stripMargin,
    compat = Map(
      "2.13" ->
        """|case Nil => scala.collection.immutable
           |case head :: next => scala.collection.immutable
           |""".stripMargin
    )
  )

  checkEditLine(
    "brace",
    """
      |object Main {
      |  ___
      |}
      |""".stripMargin,
    "List(1 -> 2).map { c@@ }",
    "List(1 -> 2).map { case ($0) => }",
    assertSingleItem = false,
    command = paramHint
  )

  check(
    "brace-label",
    """
      |object Main {
      |  List(1 -> 2).map { c@@ }
      |}
      |""".stripMargin,
    """|case (Int, Int) => scala
       |""".stripMargin,
    topLines = Some(1)
  )

  check(
    "brace-negative",
    """
      |object Main {
      |  List(1 -> 2).map(@@)
      |}
      |""".stripMargin,
    "f = : ((Int, Int)) => B",
    topLines = Some(1)
  )

  checkEditLine(
    "brace-function2",
    """
      |object Main {
      |  ___
      |}
      |""".stripMargin,
    "List(1).foldLeft(0) { cas@@ }",
    "List(1).foldLeft(0) { case ($0) => }",
    assertSingleItem = false,
    command = paramHint
  )

  // Apparently, known-direct subclasses does not work so well in 2.11.
  if (!isScala211) {
    checkEditLine(
      "infix-custom",
      """package pkg
        |object Outer {
        |  sealed trait ADT
        |  case class :+:(a: Int, b: String) extends ADT
        |}
        |object Main {
        |  val l: pkg.Outer.ADT = ???
        |  import pkg.Outer.:+:
        |  l match {
        |    ___
        |  }
        |}
        |""".stripMargin,
      "cas@@",
      "case a :+: b => $0"
    )

    checkEditLine(
      "infix-conflict",
      """
        |object Outer {
        |  sealed trait List
        |  case class ::(a: Int, b: String) extends List
        |}
        |object Main {
        |  val l: Outer.List = ???
        |  l match {
        |    ___
        |  }
        |}
        |""".stripMargin,
      "cas@@",
      // Assert we don't use infix syntax because `::` resolves to conflicting symbol in scope.
      "case Outer.::(a, b) => $0"
    )
  }

}
