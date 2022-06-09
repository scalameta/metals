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
       |    def negate(a: Boolean) = !a
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
       |        .map(5 + double(_, 7).toFloat.toInt / 8 + 6)
       |        .filter(d => d > 1)
       |        .map(double(_, 5))
       |        .map( double(_, 4).toFloat.toDouble)
       |        .map( _.toInt.compare(3))
       |        .map(_ > 2)
       |        .map(!negate(_))
       |        .m<<>>ap( true && !negate(_) && false)
       |
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |    def negate(a: Boolean) = !a
       |
       |    val res3 = {
       |         for {
       |           a <- list
       |           generatedByMetals8 <- {
       |             val m = 6
       |             Some(a + 1).map(b => b + 3 + 4)
       |           }
       |           if check(generatedByMetals8)
       |           generatedByMetals7 = generatedByMetals8
       |           if !true
       |           generatedByMetals6 = generatedByMetals7
       |           c = 7
       |           generatedByMetals5 = c - 1
       |           d = 5 + double(generatedByMetals5, 7).toFloat.toInt / 8 + 6
       |           if d > 1
       |           generatedByMetals4 = d
       |           generatedByMetals3 = double(generatedByMetals4, 5)
       |           generatedByMetals2 = double(generatedByMetals3, 4).toFloat.toDouble
       |           generatedByMetals1 = generatedByMetals2.toInt.compare(3)
       |           generatedByMetals0 = generatedByMetals1 > 2
       |           generatedByMetals = !negate(generatedByMetals0)
       |         }  yield {
       |           true && !negate(generatedByMetals) && false
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
       |        .map( double(_, 4).toFloat.toInt)
       |        .filter(d => d > 1)
       |        .map(5 + double(_, 7).toFloat.toInt / 8 + 6)
       |        .map( _.toInt.compare(3))
       |        .fl<<>>atMap(  m => Some(m * 3))
       |
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("flatMap")}
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
       |           generatedByMetals5 <- {
       |             val m = 6
       |             Some(a + 1).map(b => b + 3 + 4)
       |           }
       |           if check(generatedByMetals5)
       |           generatedByMetals4 = generatedByMetals5
       |           if !true
       |           generatedByMetals3 = generatedByMetals4
       |           c = 7
       |           generatedByMetals2 = c - 1
       |           d = double(generatedByMetals2, 4).toFloat.toInt
       |           if d > 1
       |           generatedByMetals1 = d
       |           generatedByMetals0 = 5 + double(generatedByMetals1, 7).toFloat.toInt / 8 + 6
       |           m = generatedByMetals0.toInt.compare(3)
       |           generatedByMetals <- Some(m * 3)
       |         }  yield {
       |           generatedByMetals
       |         }
       |        }
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )
}
