package tests.codeactions

import scala.meta.internal.metals.codeactions.FlatMapToForComprehensionCodeAction
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class FlatMapToForComprehensionSuite
    extends BaseCodeActionLspSuite("forComprehension") {

  check(
    "simple-for-comprehension",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |        .m<<>>ap(10 + _)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = {
       |         for {
       |           generatedByMetals <- List(1, 2, 3)
       |         }  yield {
       |           10 + generatedByMetals
       |         }
       |        }
       |}
       |""".stripMargin,
    selectedActionIndex = 1
  )

  check(
    "mixture-for-comprehension",
    """|object A {
       |    def double(x : Int, y: Int = 1) = y * x
       |    def check(x: Int) = true
       |    val list = List(1, 2, 3)
       |    def negate(a: Boolean) = !a
       |    def curried(a: Int)(b: Int) = a * b
       |
       |    val res3 = list
       |        .map(10.+)
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
       |        .map(curried(6) _ )
       |        .m<<>>ap(curried(_)(9))
       |        .map(curried(3))
       |        .map( double(_, 4).toFloat.toDouble)
       |        .map( _.toInt.compare(3))
       |        .map(_ > 2)
       |        .map(!negate(_))
       |        .map( true && !negate(_) && false)
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
       |    def curried(a: Int)(b: Int) = a * b
       |
       |    val res3 = {
       |         for {
       |           generatedByMetals12 <- list
       |           a = 10.+(generatedByMetals12)
       |           generatedByMetals11 <- {
       |             val m = 6
       |             Some(a + 1).map(b => b + 3 + 4)
       |           }
       |           if check(generatedByMetals11)
       |           generatedByMetals10 = generatedByMetals11
       |           if !true
       |           generatedByMetals9 = generatedByMetals10
       |           c = 7
       |           generatedByMetals8 = c - 1
       |           d = 5 + double(generatedByMetals8, 7).toFloat.toInt / 8 + 6
       |           if d > 1
       |           generatedByMetals7 = d
       |           generatedByMetals6 = double(generatedByMetals7, 5)
       |           generatedByMetals5 = curried(6)(generatedByMetals6)
       |           generatedByMetals4 = curried(generatedByMetals5)(9)
       |           generatedByMetals3 = curried(3)(generatedByMetals4)
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

//   writing checkNoAction does not work, since `RewriteBraces` action always shows up in this case.
//   but writing it the way I have writtern also does not work, because the result has type errors.
//
//  check("double-replacement-placeHolder",
//    """|object A{
//       |def multiply(a: Int, b: Int) = a * b
//       |val res = List(1, 2, 3).m<<>>ap(multiply(_, _))
//      |}""".stripMargin,
//    RewriteBracesParensCodeAction.toBraces("map"),
//    """|object A{
//       |def multiply(a: Int, b: Int) = a * b
//       |val res = List(1, 2, 3).map{multiply(_, _)}
//       |}""".stripMargin,
//    expectError = true
//  )
  // expectError = true did not cause the test to pass. the error is:
//  -a/src/main/scala/a/A.scala:3:29: error: type mismatch;
//  - found   : (Int, Int) => Int
//  - required: Int => ?
//  -val res = List(1, 2, 3).map{multiply(_, _)}
//  -

}
