package tests.codeactions

import scala.meta.internal.metals.codeactions.InsertInferredType
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class InsertInferredTypeLspSuite
    extends BaseCodeActionLspSuite("insertInferredType") {

  check(
    "val",
    """|package a
       |
       |object A {
       |  val al<<>>pha = 123
       |}
       |""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  val alpha: Int = 123
       |}
       |""".stripMargin
  )

  check(
    "def",
    """|package a
       |
       |object A {
       |  def al<<>>pha() = 123
       |}
       |""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |""".stripMargin,
    """|package a
       |
       |object A {
       |  def alpha(): Int = 123
       |}
       |""".stripMargin
  )

  check(
    "for-comprehension",
    """|object A{
       |  for {
       |    fir<<>>st <- 1 to 10
       |    second <- 1 to 11
       |  } yield (first, second)
       |}""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|object A{
       |  for {
       |    first: Int <- 1 to 10
       |    second <- 1 to 11
       |  } yield (first, second)
       |}
       |""".stripMargin
  )

  check(
    "for-comprehension-var",
    """|object A{
       |  for {
       |    first <- 1 to 10
       |    sec<<>>ond = first
       |  } yield (first, second)
       |}""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|object A{
       |  for {
       |    first <- 1 to 10
       |    second: Int = first
       |  } yield (first, second)
       |}
       |""".stripMargin
  )

  checkNoAction(
    "for-comprehension-existing",
    """|object A{
       |  for {
       |    fir<<>>st: Int <- 1 to 10
       |    second <- 1 to 11
       |  } yield (first, second)
       |}""".stripMargin
  )

  check(
    "lambda",
    """|object A{
       |  val list = "123".map(c<<>>h => ch.toInt)
       |}""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |${RewriteBracesParensCodeAction.toBraces}
        |""".stripMargin,
    """|object A{
       |  val list = "123".map((ch: Char) => ch.toInt)
       |}
       |""".stripMargin
  )

  check(
    "lambda-brace",
    """|object A{
       |  val list = "123".map{c<<>>h => ch.toInt}
       |}""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |${RewriteBracesParensCodeAction.toParens}
        |""".stripMargin,
    """|object A{
       |  val list = "123".map{ch: Char => ch.toInt}
       |}
       |""".stripMargin
  )

  check(
    "val-pattern",
    """|object A{
       |  val (fir<<>>st, second) = (List(1), List(""))
       |}""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|object A{
       |  val (first: List[Int], second) = (List(1), List(""))
       |}
       |""".stripMargin
  )

  check(
    "match",
    """|object A{
       |  (List(1), List("")) match {
       |    case (List(2), _) =>
       |    case (first, se<<>>cond) =>
       |  }
       |}""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|object A{
       |  (List(1), List("")) match {
       |    case (List(2), _) =>
       |    case (first, second: List[String]) =>
       |  }
       |}
       |""".stripMargin
  )

  check(
    "match-option",
    """|object A{
       |  Option(1) match {
       |    case Some(<<t>>) => t
       |    case None =>
       |  }
       |}""".stripMargin,
    s"""|${InsertInferredType.insertTypeToPattern}
        |""".stripMargin,
    """|object A{
       |  Option(1) match {
       |    case Some(t: Int) => t
       |    case None =>
       |  }
       |}
       |""".stripMargin
  )

  checkNoAction(
    "match-bind",
    """|object A{
       |  (1, 2) match {
       |    case <<num>> @ (first, _) => "Two!"
       |    case otherDigit => "Not two!"
       |  }
       |}""".stripMargin
  )

  checkNoAction(
    "existing-type",
    """|package a
       |
       |object A {
       |  val al<<>>pha: Int = 123
       |}
       |""".stripMargin
  )

  check(
    "auto-import",
    """|package a
       |
       |object A {
       |  var al<<>>pha = List(123).toBuffer
       |}
       |""".stripMargin,
    s"""|${InsertInferredType.insertType}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.mutable.Buffer
       |
       |object A {
       |  var alpha: Buffer[Int] = List(123).toBuffer
       |}
       |""".stripMargin
  )

}
