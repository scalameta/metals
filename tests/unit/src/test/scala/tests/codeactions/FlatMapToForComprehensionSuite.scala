package tests.codeactions

import scala.meta.internal.metals.codeactions.FlatMapToForComprehensionCodeAction
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class FlatMapToForComprehensionSuite
    extends BaseCodeActionLspSuite("forComprehension") {

  check(
    "mixture",
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |
       |    val res3 = list
       |        .flatMap{
       |              a =>
       |                      {
       |                        val m = 6
       |                        Some(a + 1).map(b => b + 3 + 4)
       |                      }
       |
       |        }.filter (check)
       |        .filterNot(_ =>  true)
       |        .map(_ => 7)
       |        .map(c => c - 1)
       |        .filter(d => d > 1)
       |        .map( double(_, 4).toFloat.toDouble)
       |        .m<<>>ap( _.toInt.compare(3))
       |
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |
       |    val res3 = {
       |         for {
       |           a <- list
       |           generatedByMetals3 <- {
       |                                              val m = 6
       |                                              Some(a + 1).map(b => b + 3 + 4)
       |                                            }
       |          if (check(generatedByMetals3))
       |           generatedByMetals2 = generatedByMetals3
       |          if !(true)
       |           generatedByMetals1 = generatedByMetals2
       |           c = 7
       |           d = c - 1
       |          if (d > 1)
       |           generatedByMetals0 = d
       |           generatedByMetals = double(generatedByMetals0, 4).toFloat.toDouble
       |         } yield {
       |                      generatedByMetals.toInt.compare(3)
       |         }
       |        }
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "flatten-for-flatMap",
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |
       |    val res3 = list
       |        .flatMap{
       |              a => {
       |                        val m = 6
       |                        Some(a + 1).map(b => b + 3 + 4)
       |                    }
       |
       |        }.filter (check)
       |        .filterNot(_ =>  true)
       |        .map(_ => 7)
       |        .map(c => c - 1)
       |        .filter(d => d > 1)
       |        .map( double(_, 4).toFloat.toDouble)
       |        .map( _.toInt.compare(3))
       |        .fl<<>>atMap(  m => Some(m * 3))
       |
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |
       |    val res3 = {
       |         for {
       |           a <- list
       |           generatedByMetals3 <- {
       |                                              val m = 6
       |                                              Some(a + 1).map(b => b + 3 + 4)
       |                                          }
       |          if (check(generatedByMetals3))
       |           generatedByMetals2 = generatedByMetals3
       |          if !(true)
       |           generatedByMetals1 = generatedByMetals2
       |           c = 7
       |           d = c - 1
       |          if (d > 1)
       |           generatedByMetals0 = d
       |           generatedByMetals = double(generatedByMetals0, 4).toFloat.toDouble
       |           m = generatedByMetals.toInt.compare(3)
       |           generatedByMetals4 <- Some(m * 3)
       |         } yield {
       |                      generatedByMetals4
       |         }
       |        }
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )
}
