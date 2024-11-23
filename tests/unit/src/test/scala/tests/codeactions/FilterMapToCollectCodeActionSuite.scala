package tests.codeactions

import scala.meta.internal.metals.codeactions.FilterMapToCollectCodeAction
import scala.meta.internal.metals.codeactions.FlatMapToForComprehensionCodeAction
import scala.meta.internal.metals.codeactions.RewriteBracesParensCodeAction

class FilterMapToCollectCodeActionSuite
    extends BaseCodeActionLspSuite("filterMapToCollect") {
  val simpleOutput: String =
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.collect {
       |    case x if x > 2 =>
       |      x * 2
       |  }
       |}
       |""".stripMargin

  check(
    "cursor-on-filter",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.fil<<>>ter(x => x > 2).map(x => x * 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    simpleOutput,
    selectedActionIndex = 2,
  )

  check(
    "cursor-on-filter-newline",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.fil<<>>ter{
       |    x => x > 2
       |  }.map{
       |    x => x * 2
       |  }
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    simpleOutput,
    selectedActionIndex = 2,
  )

  check(
    "cursor-on-map",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.filter(x => x > 2).m<<>>ap(x => x * 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    simpleOutput,
    selectedActionIndex = 2,
  )

  check(
    "higher-order-function",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def check = (x: Int) => x > 2
       |  def double = (x: Int) => x * 2
       |  numbers.fil<<>>ter(check).map(double)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def check = (x: Int) => x > 2
       |  def double = (x: Int) => x * 2
       |  numbers.collect {
       |    case x if check(x) =>
       |      double(x)
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "higher-order-function-rename-filter-argument",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def double = (x: Int) => x * 2
       |  numbers.fil<<>>ter(x => x > 0).map(double)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def double = (x: Int) => x * 2
       |  numbers.collect {
       |    case x if x > 0 =>
       |      double(x)
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "higher-order-function-rename-map-argument",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def check = (x: Int) => x > 2
       |  numbers.fil<<>>ter(check).map(y => y * 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  def check = (x: Int) => x > 2
       |  numbers.collect {
       |    case x if check(x) =>
       |      x * 2
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "multiple-map-calls",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.fil<<>>ter(x => x > 2).map(x => x * 2).map(x => x * 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.collect {
       |    case x if x > 2 =>
       |      x * 2
       |  }.map(x => x * 2)
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "complex-predicate",
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users.fil<<>>ter(u => u._2 >= 18 && u._1.startsWith("A")).map(u => u._1)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users.collect {
       |    case u if u._2 >= 18 && u._1.startsWith("A") =>
       |      u._1
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "multiline-predicate",
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users
       |    .fil<<>>ter(u =>
       |      u._2 >= 18 &&
       |        u._1.startsWith("A")
       |    )
       |    .map { u =>
       |      // format user name into multline card
       |      val card = Seq(
       |        s"Name: ${u._1}",
       |        s"Age: ${u._2}",
       |      )
       |      card.mkString("\n")
       |    }
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users.collect {
       |    case u if u._2 >= 18 && u._1.startsWith("A") =>
       |      val card = Seq(s"Name: ${
       |        u._1
       |      }", s"Age: ${
       |        u._2
       |      }")
       |      card.mkString("\n")
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "multiline-predicate-block",
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users
       |    .fil<<>>ter { u =>
       |      val nameLength = u._1.length
       |
       |      nameLength > 3
       |    }
       |    .map { u => u._2 }
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toParens("map")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val users = List(("Alice", 25), ("Bob", 30))
       |  users.collect {
       |    case u if {
       |      val nameLength = u._1.length
       |      nameLength > 3
       |    } =>
       |      u._2
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )

  check(
    "with-type-annotations",
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.fil<<>>ter((x: Int) => x > 2).map((x: Int) => x * 2)
       |}
       |""".stripMargin,
    s"""|${RewriteBracesParensCodeAction.toBraces("filter")}
        |${FlatMapToForComprehensionCodeAction.flatMapToForComprehension}
        |${FilterMapToCollectCodeAction.title}
        |""".stripMargin,
    """|object Main {
       |  val numbers = List(1, 2, 3, 4)
       |  numbers.collect {
       |    case x: Int if x > 2 =>
       |      x * 2
       |  }
       |}
       |""".stripMargin,
    selectedActionIndex = 2,
  )
}
