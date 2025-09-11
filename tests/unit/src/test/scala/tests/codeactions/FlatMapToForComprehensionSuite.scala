package tests.codeactions

import scala.meta.internal.metals.codeactions.{
  FlatMapToForComprehensionCodeAction,
  RewriteBracesParensCodeAction,
}

class FlatMapToForComprehensionSuite
    extends BaseCodeActionLspSuite("forComprehension") {

  check(
    "partial-function-for-comprehension",
    """|object A {
       |  case class Extractable(first: String, second: List[(Int, String)])
       |  val result = List((1, (2, 3, 4)), (1 , (2, 3.1, 4.1))).map(m => (m._1, m._2))
       |  .flatMap{
       |    case (j, (k: Int, l , n)) => Some(j + 1, (k + 1, l , n))
       |  }
       |  .flatMap{
       |    case (a, (b, c , d)) => Some(a + 1, (b + 1, c , a))
       |  }
       |  .map{
       |    case (e, (f, g:Int, _)) if e > 3 => e + f + g
       |    case (h, (i, _, _)) => h + i
       |  }.m<<>>ap( num => s"the value is $num")
       |  .map{
       |    case s"the value is $numVal" => Extractable( numVal, List((numVal.toInt, numVal), (4, "15")))
       |  }
       |  .map{
       |    case Extractable( first, (r, s)::List((second: Int, third))) => first + r + s + second + third
       |  }
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |  case class Extractable(first: String, second: List[(Int, String)])
       |  val result = for {
       |    m <- List((1, (2, 3, 4)), (1, (2, 3.1d, 4.1d)))
       |    (j, (k: Int, l, n)) = (m._1, m._2)
       |    (a, (b, c, d)) <- Some(j + 1, (k + 1, l, n))
       |    generatedByMetals <- Some(a + 1, (b + 1, c, a))
       |    num = generatedByMetals match {
       |      case (e, (f, g: Int, _)) if e > 3 =>
       |        e + f + g
       |      case (h, (i, _, _)) =>
       |        h + i
       |    }
       |    s"the value is ${numVal}" = s"the value is $num"
       |    Extractable(first, (r, s) :: List((second: Int, third))) = Extractable(numVal, List((numVal.toInt, numVal), (4, "15")))
       |  } yield {
       |    first + r + s + second + third
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

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
       |    val res3 = for {
       |          generatedByMetals <- List(1, 2, 3)
       |        } yield {
       |          10 + generatedByMetals
       |        }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "empty-arguments-list-apply",
    """|object A {
       |    val first = 1
       |    val second = 2
       |    val third = 3
       |    def goToLink(a: Int, b: Int, c: Int, d: Int): Option[Int] = if( a > 3) Some(d) else None
       |
       |    class B {
       |      def generateList(): List[Int] = List(1, 2, 3)
       |    }
       |
       |    val res3 = new B()
       |        .generateList()
       |        .fla<<>>tMap(goToLink(first, second, third, _))
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("flatMap")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val first = 1
       |    val second = 2
       |    val third = 3
       |    def goToLink(a: Int, b: Int, c: Int, d: Int): Option[Int] = if( a > 3) Some(d) else None
       |
       |    class B {
       |      def generateList(): List[Int] = List(1, 2, 3)
       |    }
       |
       |    val res3 = for {
       |          generatedByMetals <- new B().generateList()
       |          generatedByMetals1 <- goToLink(first, second, third, generatedByMetals)
       |        } yield {
       |          generatedByMetals1
       |        }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
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
       |        .m<<>>ap(double(_, 5))
       |        .map(curried(6) _ )
       |        .map(curried(_)(9))
       |        .map(curried(3))
       |        .map( double(_, 4).toFloat.toDouble)
       |        .map( _.toInt.compare(3))
       |        .map(_ > 2)
       |        .map(!negate(_))
       |        .map( true && !negate(_) && false)
       |        .sortBy(x => x)
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
       |          for {
       |            generatedByMetals13 <- list
       |            a = 10.+(generatedByMetals13)
       |            generatedByMetals12 <- {
       |              val m = 6
       |              Some(a + 1).map(b => b + 3 + 4)
       |            }
       |            if check(generatedByMetals12)
       |            generatedByMetals11 = generatedByMetals12
       |            if !true
       |            generatedByMetals10 = generatedByMetals11
       |            c = 7
       |            generatedByMetals9 = c - 1
       |            d = 5 + double(generatedByMetals9, 7).toFloat.toInt / 8 + 6
       |            if d > 1
       |            generatedByMetals8 = d
       |            generatedByMetals7 = double(generatedByMetals8, 5)
       |            generatedByMetals6 = curried(6)(generatedByMetals7)
       |            generatedByMetals5 = curried(generatedByMetals6)(9)
       |            generatedByMetals4 = curried(3)(generatedByMetals5)
       |            generatedByMetals3 = double(generatedByMetals4, 4).toFloat.toDouble
       |            generatedByMetals2 = generatedByMetals3.toInt.compare(3)
       |            generatedByMetals1 = generatedByMetals2 > 2
       |            generatedByMetals = !negate(generatedByMetals1)
       |          } yield {
       |            true && !negate(generatedByMetals) && false
       |          }
       |        }
       |        .sortBy(x => x)
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
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
       |    val res3 = for {
       |          a <- list
       |          generatedByMetals6 <- {
       |            val m = 6
       |            Some(a + 1).map(b => b + 3 + 4)
       |          }
       |          if check(generatedByMetals6)
       |          generatedByMetals5 = generatedByMetals6
       |          if !true
       |          generatedByMetals4 = generatedByMetals5
       |          c = 7
       |          generatedByMetals3 = c - 1
       |          d = double(generatedByMetals3, 4).toFloat.toInt
       |          if d > 1
       |          generatedByMetals2 = d
       |          generatedByMetals1 = 5 + double(generatedByMetals2, 7).toFloat.toInt / 8 + 6
       |          m = generatedByMetals1.toInt.compare(3)
       |          generatedByMetals <- Some(m * 3)
       |        } yield {
       |          generatedByMetals
       |        }
       |
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "double-replacement-placeHolder",
    """|object A{
       |def multiply(a: Int, b: Int) = a * b
       |val res = List(1, 2, 3).m<<>>ap(multiply(_, _))
       |}""".stripMargin,
    RewriteBracesParensCodeAction.toBraces("map"),
    """|object A{
       |def multiply(a: Int, b: Int) = a * b
       |val res = List(1, 2, 3).map{multiply(_, _)}
       |}
       |""".stripMargin,
    expectError = true,
    expectNoDiagnostics = false,
  )

  check(
    "overlapping-parameter-names",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |               .map(x => x + 1)
       |               .m<<>>ap(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |                 generatedByMetals <- List(1, 2, 3)
       |                 x = generatedByMetals + 1
       |               } yield {
       |                 x + 2
       |               }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "nested-overlapping-parameter-names",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        for (x <- 1 to 5)
       |           println(x)
       |        x + 3
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          for (x <- 1 to 5) println(x)
       |          generatedByMetals + 3
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-nested-for-comprehension",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        for (y <- 1 to 5;
       |             m <- 1 to x if m + y == x;
       |             p = x)
       |           println(x + y + p)
       |        x + 3
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          for (y <- 1 to 5; m <- 1 to generatedByMetals; if m + y == generatedByMetals; p = generatedByMetals) println(generatedByMetals + y + p)
       |          generatedByMetals + 3
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-anonymous-and-partial-function",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        List(1, 2, 3).map(_ * x).foreach { case i => println(i + x) }
       |        x
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          List(1, 2, 3).map(_ * generatedByMetals).foreach {
       |            case i =>
       |              println(i + generatedByMetals)
       |          }
       |          generatedByMetals
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-throw-interpolation",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        throw new java.lang.IllegalStateException(s"X: ${x}")
       |        x
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          throw new java.lang.IllegalStateException(s"X: ${
       |            generatedByMetals
       |          }")
       |          generatedByMetals
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-if-else",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        if (x < 2) println(x)
       |        else println(x.toFloat)
       |        x
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          if (generatedByMetals < 2) println(generatedByMetals) else println(generatedByMetals.toFloat)
       |          generatedByMetals
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-match",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .flatM<<>>ap { x =>
       |        Option(x).filter(_ != x % 2) match {
       |          case Some(x) => Some(x + 5)
       |          case _ => None
       |        }
       |      }.map { x =>
       |        x match {
       |          case 1 | 2 => x + 5
       |          case a if x > 5 => x + 1
       |          case b if b > 4 => x * 2
       |          case x if x > 3 => x - 1
       |          case x: Int => x
       |        }
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("flatMap")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals1 <- List(1, 2, 3)
       |        generatedByMetals <- Option(generatedByMetals1).filter(_ != generatedByMetals1 % 2) match {
       |          case Some(x) =>
       |            Some(x + 5)
       |          case _ =>
       |            None
       |        }
       |        x = generatedByMetals match {
       |          case 1 | 2 =>
       |            generatedByMetals + 5
       |          case a if generatedByMetals > 5 =>
       |            generatedByMetals + 1
       |          case b if b > 4 =>
       |            generatedByMetals * 2
       |          case x if x > 3 =>
       |            x - 1
       |          case x: Int =>
       |            x
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-try-catch",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        try {
       |          x / 0
       |        } catch {
       |          case e: java.lang.ArithmeticException => x
       |        } finally {
       |          println(x)
       |        }
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = try {
       |          generatedByMetals / 0
       |        } catch {
       |          case e: java.lang.ArithmeticException => generatedByMetals
       |        } finally {
       |          println(generatedByMetals)
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-assignment",
    """|object A {
       |    var z = 0
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        z = x
       |        x + 3
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    var z = 0
       |    val res3 = for {
       |        generatedByMetals <- List(1, 2, 3)
       |        x = {
       |          z = generatedByMetals
       |          generatedByMetals + 3
       |        }
       |      } yield {
       |        x + 2
       |      }
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
  )

  check(
    "overlapping-parameter-names-unsupported-match",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .flatM<<>>ap { x =>
       |        Option(Option(x)) match {
       |          case Some(Some(x)) => Some(x + 5)
       |          case _ => None
       |        }
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("flatMap")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .flatMap { x =>
       |        Option(Option(x)) match {
       |          case Some(Some(x)) => Some(x + 5)
       |          case _ => None
       |        }
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
    expectError = true,
    expectNoDiagnostics = false,
  )

  check(
    "complex-overlapping-names-unsupported-val",
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .m<<>>ap { x =>
       |        val x = 5
       |        x + 3
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |""".stripMargin,
    """|object A {
       |    val res3 = List(1, 2, 3)
       |      .map { x =>
       |        val x = 5
       |        x + 3
       |      }.map(x => x + 2)
       |}
       |""".stripMargin,
    selectedActionIndex = 1,
    expectError = true,
    expectNoDiagnostics = false,
  )

}
