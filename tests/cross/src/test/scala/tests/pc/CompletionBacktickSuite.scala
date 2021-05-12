package tests.pc

import tests.BaseCompletionSuite

class CompletionBacktickSuite extends BaseCompletionSuite {

  check(
    "keyword",
    s"""|object Main {
        |  val `type` = 42
        |  Main.typ@@
        |}
        |""".stripMargin,
    """|`type`: Int
       |""".stripMargin,
    filterText = "type",
    compat = Map(
      "3.0" -> "type: Int"
    )
  )

  checkEdit(
    "keyword-edit",
    s"""|object Main {
        |  val `type` = 42
        |  Main.typ@@
        |}
        |""".stripMargin,
    """|object Main {
       |  val `type` = 42
       |  Main.`type`
       |}
       |""".stripMargin,
    filterText = "type"
  )

  check(
    "space",
    s"""|object Main {
        |  val `hello world` = 42
        |  Main.hello@@
        |}
        |""".stripMargin,
    """|`hello world`: Int
       |""".stripMargin,
    filterText = "hello world",
    compat = Map(
      "3.0" -> "hello world: Int"
    )
  )

  check(
    "comment",
    s"""|object Main {
        |  val `///` = 42
        |  Main./@@
        |}
        |""".stripMargin,
    """|`///`: Int
       |""".stripMargin,
    filterText = "///",
    compat = Map(
      "3.0" -> "///: Int"
    )
  )

  check(
    "named-arg".tag(IgnoreScala3),
    """|object Main {
       |  def foo(`type`: Int) = 42
       |  foo(type@@)
       |}
       |""".stripMargin,
    """`type` = : Int
      |""".stripMargin,
    filterText = "type",
    compat = Map(
      "3.0" -> ""
    )
  )

  check(
    "normal",
    """|object Main {
       |  val `spaced` = 42
       |  spaced@@
       |}
       |""".stripMargin,
    // NOTE(olafur) expected output is not backticked because the compiler symbol does not
    // distinguish if the symbol was defined with backticks in source.
    """spaced: Int
      |""".stripMargin,
    filterText = ""
  )

  check(
    "negative",
    """|object Main {
       |  val `type` = 42
       |  Main.`typ@@
       |}
       |""".stripMargin,
    // NOTE(olafur) expected output is empty because the source does not tokenize due to unclosed identifier.
    // It would be nice to fix this limitation down the road.
    "",
    filter = _.contains("`type`")
  )

}
