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
    )
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
      "3" -> "hello world: Int"
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
      "3" -> "///: Int"
    )
  )

  check(
    "named-arg",
    """|object Main {
       |  def foo(`type`: Int) = 42
       |  foo(type@@)
       |}
       |""".stripMargin,
    """|`type` = : Int
       |TypeNotPresentException java.lang
       |""".stripMargin
  )

  check(
    "normal",
    """|object Main {
       |  val `spaced` = 42
       |  spaced@@
       |}
       |""".stripMargin,
    """`spaced`: Int
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
          |  `$keyword`($$0)
          |}
          |""".stripMargin,
      filter = _.contains("a: String")
    )

  checkEdit(
    "soft-keyword-select",
    """|object Main {
       |  case class Pos(start: Int, end: Int)
       |  val a = Pos(1,2)
       |  val b = a.end@@
       |}
       |""".stripMargin,
    """|object Main {
       |  case class Pos(start: Int, end: Int)
       |  val a = Pos(1,2)
       |  val b = a.end
       |}
       |""".stripMargin
  )

  checkEdit(
    "soft-keyword-ident",
    """|object Main {
       |  case class Pos(start: Int, end: Int) {
       |    val point = start - end@@
       |  }
       |}
       |""".stripMargin,
    """|object Main {
       |  case class Pos(start: Int, end: Int) {
       |    val point = start - end
       |  }
       |}
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|object Main {
           |  case class Pos(start: Int, end: Int) {
           |    val point = start - `end`
           |  }
           |}
           |""".stripMargin
    )
  )

  checkEdit(
    "soft-keyword-extension".tag(IgnoreScala2),
    """|object A {
       |  extension (a: String) def end = a.last
       |}
       |object Main {
       |  val a = "abc".end@@
       |}
       |""".stripMargin,
    """|import A.end
       |object A {
       |  extension (a: String) def end = a.last
       |}
       |object Main {
       |  val a = "abc".end
       |}
       |""".stripMargin,
    filter = _.contains("end: Char")
  )

  checkEdit(
    "keyword-select",
    """|object Main {
       |  case class Pos(start: Int, `lazy`: Boolean)
       |  val a = Pos(1,true)
       |  val b = a.laz@@
       |}
       |""".stripMargin,
    """|object Main {
       |  case class Pos(start: Int, `lazy`: Boolean)
       |  val a = Pos(1,true)
       |  val b = a.`lazy`
       |}
       |""".stripMargin
  )

  check(
    "preserve-backticks",
    """|trait Foo {
       |  def withoutBackticks: Unit
       |  def `withUnecessaryBackticks`: Unit
       |  def `with necessary backticks`: Unit
       |
       |  with@@
       |}
       |""".stripMargin,
    """|`with necessary backticks`: Unit
       |`withUnecessaryBackticks`: Unit
       |withoutBackticks: Unit
       |""".stripMargin,
    topLines = Some(3)
  )
}
