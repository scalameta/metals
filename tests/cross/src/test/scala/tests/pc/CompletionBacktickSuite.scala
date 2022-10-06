package tests.pc

import munit.Location
import tests.BaseCompletionSuite

class CompletionBacktickSuite extends BaseCompletionSuite {

  check(
    "keyword",
    """|object Main {
       |  val `type` = 42
       |  Main.typ@@
       |}
       |""".stripMargin,
    """|`type`: Int
       |""".stripMargin,
    filterText = "type",
    compat = Map(
      "3" -> "type: Int"
    ),
  )

  checkEdit(
    "keyword-edit",
    """|object Main {
       |  val `type` = 42
       |  Main.typ@@
       |}
       |""".stripMargin,
    """|object Main {
       |  val `type` = 42
       |  Main.`type`
       |}
       |""".stripMargin,
    filterText = "type",
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
      "3" -> "hello world: Int"
    ),
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
      "3" -> "///: Int"
    ),
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
      "3" -> ""
    ),
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
    filterText = "",
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
    filter = _.contains("`type`"),
  )

  // https://dotty.epfl.ch/docs/internals/syntax.html#soft-keywords
  List("infix", "inline", "opaque", "open", "transparent", "as", "derives",
    "end", "extension", "throws", "using").foreach(softKeywordCheck)

  private def softKeywordCheck(keyword: String)(implicit loc: Location) =
    checkEdit(
      s"'$keyword'-keyword-named-method-edit".tag(IgnoreScala2),
      s"""|object Main {
          |  def $keyword(a: String) = a
          |  ${keyword}@@
          |}
          |""".stripMargin,
      s"""|object Main {
          |  def $keyword(a: String) = a
          |  `$keyword`
          |}
          |""".stripMargin,
      filter = _.contains("a: String"),
    )

}
