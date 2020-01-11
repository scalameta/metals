package tests.codeactions

import scala.meta.internal.metals.codeactions.ImportMissingSymbol

object ImportMissingSymbolLspSuite
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
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
    expectNoDiagnostics = true
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
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin,
    expectNoDiagnostics = true
  )

  check(
    "multi-same-line",
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
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin
  )

  check(
    "multi-across-lines",
    """|package a
       |
       |object A {
       |  val f = Future.successful(<<Instant.now)
       |  val b = ListBuffer.newBuilder[Int]>>
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.title("Instant", "java.time")}
        |${ImportMissingSymbol.title("ListBuffer", "scala.collection.mutable")}
        |""".stripMargin,
    """|package a
       |
       |import java.time.Instant
       |
       |object A {
       |  val f = Future.successful(Instant.now)
       |  val b = ListBuffer.newBuilder[Int]
       |}
       |""".stripMargin
  )

}
