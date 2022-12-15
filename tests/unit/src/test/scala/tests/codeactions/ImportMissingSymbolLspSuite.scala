package tests.codeactions

import scala.meta.internal.metals.codeactions.ConvertToNamedArguments
import scala.meta.internal.metals.codeactions.CreateNewSymbol
import scala.meta.internal.metals.codeactions.ExtractMethodCodeAction
import scala.meta.internal.metals.codeactions.ImportMissingSymbol

class ImportMissingSymbolLspSuite
    extends BaseCodeActionLspSuite("importMissingSymbol") {

  check(
    "basic",
    """|package a
       |
       |object A {
       |  val f = <<Future>>.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
  )

  check(
    "enclosed-range",
    """|package a
       |
       |object A {
       |  val f = Fu<<tu>>re.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${CreateNewSymbol.title("Future")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
  )

  check(
    "multiple-tries",
    """|package a
       |
       |object A {
       |  val f = <<Try{}
       |  val g = Try{}>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Try", "scala.util")}
        |${CreateNewSymbol.title("Try")}
        |""".stripMargin,
    """|package a
       |
       |import scala.util.Try
       |
       |object A {
       |  val f = Try{}
       |  val g = Try{}
       |}
       |""".stripMargin,
  )

  check(
    "multi-same-line-ambiguous",
    """|package a
       |
       |object A {
       |  val f = <<Future.successful(Instant.now)>>
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |${ExtractMethodCodeAction.title("object `A`")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

  check(
    "multi-across-lines-non-ambiguous",
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(<<Instant.now)
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

  check(
    "multi-across-lines-ambiguous-and-non-ambiguous",
    """|package a
       |
       |object A {
       |  val f = <<Future.successful(Instant.now)
       |  val a = "  " + "  " + "  "
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val a = "  " + "  " + "  "
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

  check(
    "multi-same-symbol",
    """|package a
       |
       |object A {
       |  val f = <<Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |  val t = Future.successful(ListBuffer.empty)>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.allSymbolsTitle}
        |${ImportMissingSymbol.title("Future", "scala.concurrent")}
        |${ImportMissingSymbol.title("Future", "java.util.concurrent")}
        |${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |${CreateNewSymbol.title("Future")}
        |${CreateNewSymbol.title("Instant")}
        |${CreateNewSymbol.title("ListBuffer")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |import scala.collection.mutable.ListBuffer
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |  val t = Future.successful(ListBuffer.empty)
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

  check(
    "multi-package-object",
    """|package a
       |
       |package object b {
       | object A {
       |    val f = Future.successful(<<Instant.now)>>
       |    val b = ListBuffer.newBuilder[Int]
       | }
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |${CreateNewSymbol.title("Instant")}
        |${ConvertToNamedArguments.title("successful(...)")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |
       |package object b {
       | object A {
       |    val f = Future.successful(Instant.now)
       |    val b = ListBuffer.newBuilder[Int]
       | }
       |}
       |""".stripMargin,
    expectNoDiagnostics = false,
  )

}
