package tests

import scala.meta.internal.metals.QuickFixes.ImportMissingSymbol

object ImportMissingSymbolLspSuite extends BaseCodeActionLspSuite("importMissingSymbol") {

  check(
    "auto-import",
    """|package a
       |
       |object A {
       |  val f = Fut@@ure.successful(2)
       |}
       |""".stripMargin,
    s"""|${ImportMissingSymbol.label("Future", "scala.concurrent")}
        |${ImportMissingSymbol.label("Future", "java.util.concurrent")}
        |""".stripMargin,
    """|package a
       |
       |import scala.concurrent.Future
       |
       |object A {
       |  val f = Future.successful(2)
       |}
       |""".stripMargin
  )

}
