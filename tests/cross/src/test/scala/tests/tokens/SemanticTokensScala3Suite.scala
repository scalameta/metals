package tests.tokens

import tests.BaseSemanticTokensSuite

class SemanticTokensScala3Suite extends BaseSemanticTokensSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  check(
    "enum",
    """|package <<example>>/*namespace*/
       |
       |enum <<FooEnum>>/*enum,abstract*/:
       |  case <<Bar>>/*enum*/, <<Baz>>/*enum*/
       |object <<FooEnum>>/*class*/
       |""".stripMargin,
  )

  check(
    "enum1",
    """|package <<example>>/*namespace*/
       |
       |enum <<FooEnum>>/*enum,abstract*/:
       |  case <<A>>/*enum*/(<<a>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |  case <<B>>/*enum*/(<<a>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<b>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |  case <<C>>/*enum*/(<<a>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<b>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<c>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |
       |""".stripMargin,
  )

  // Issue: Sequential parameters are not highlighted
  // https://github.com/scalameta/metals/issues/4985
  check(
    "named-arguments",
    s"""|package <<example>>/*namespace*/
        |
        |def <<m>>/*method,definition*/(<<xs>>/*parameter,readonly,declaration*/: <<Int>>/*class,abstract*/*) = <<xs>>/*parameter,readonly*/.<<map>>/*method*/(<<_>>/*parameter,readonly*/ <<+>>/*method*/ 1)
        |val <<a>>/*variable,definition,readonly*/ = <<m>>/*method*/(xs = 1,2,3)
        |""".stripMargin,
  )

  // Issue: Structural types are not highlighted
  // https://github.com/scalameta/metals/issues/4984
  check(
    "structural-types",
    s"""|package <<example>>/*namespace*/
        |
        |import <<reflect>>/*namespace*/.<<Selectable>>/*class*/.<<reflectiveSelectable>>/*method*/
        |
        |object <<StructuralTypes>>/*class*/:
        |  type <<User>>/*type,definition*/ = {
        |    def <<name>>/*method,declaration*/: <<String>>/*type*/
        |    def <<age>>/*method,declaration*/: <<Int>>/*class,abstract*/
        |  }
        |
        |  val <<user>>/*variable,definition,readonly*/ = null.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
        |  <<user>>/*variable,readonly*/.name
        |  <<user>>/*variable,readonly*/.age
        |
        |  val <<V>>/*variable,definition,readonly*/: <<Object>>/*class*/ {
        |    def <<scalameta>>/*method,declaration*/: <<String>>/*type*/
        |  } = new:
        |    def <<scalameta>>/*method,definition*/ = "4.0"
        |  <<V>>/*variable,readonly*/.scalameta
        |end StructuralTypes
        |""".stripMargin,
    compat = Map(
      ">=3.3.1-RC1-bin-20230318-7226ba6-NIGHTLY" ->
        s"""|package <<example>>/*namespace*/
            |
            |import <<reflect>>/*namespace*/.<<Selectable>>/*class*/.<<reflectiveSelectable>>/*method*/
            |
            |object <<StructuralTypes>>/*class*/:
            |  type <<User>>/*type*/ = {
            |    def <<name>>/*method*/: <<String>>/*type*/
            |    def <<age>>/*method*/: <<Int>>/*class,abstract*/
            |  }
            |
            |  val <<user>>/*variable,readonly*/ = null.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
            |  <<user>>/*variable,readonly*/.<<name>>/*method*/
            |  <<user>>/*variable,readonly*/.<<age>>/*method*/
            |
            |  val <<V>>/*variable,readonly*/: <<Object>>/*class*/ {
            |    def <<scalameta>>/*method*/: <<String>>/*type*/
            |  } = new:
            |    def <<scalameta>>/*method*/ = "4.0"
            |  <<V>>/*variable,readonly*/.<<scalameta>>/*method*/
            |end StructuralTypes
            |""".stripMargin
    ),
  )

  check(
    "vars",
    s"""|package <<example>>/*namespace*/
        |
        |object <<A>>/*class*/ {
        |  val <<a>>/*variable,definition,readonly*/ = 1
        |  var <<b>>/*variable,definition*/ = 2
        |  val <<c>>/*variable,definition,readonly*/ = <<List>>/*class*/(1,<<a>>/*variable,readonly*/,<<b>>/*variable*/)
        |  <<b>>/*variable*/ = <<a>>/*variable,readonly*/
        |""".stripMargin,
  )

  check(
    "predef",
    """
      |object <<Main>>/*class*/ {
      |  val <<a>>/*variable,readonly*/ = <<List>>/*class*/(1,2,3)
      |  val <<y>>/*variable,readonly*/ = <<Vector>>/*class*/(1,2)
      |  val <<z>>/*variable,readonly*/ = <<Set>>/*class*/(1,2,3)
      |  val <<w>>/*variable,readonly*/ = <<Right>>/*class*/(1)
      |}""".stripMargin,
  )

  check(
    "case-class",
    """|case class <<Foo>>/*class*/(<<i>>/*variable,readonly*/: <<Int>>/*class,abstract*/, <<j>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
       |
       |object <<A>>/*class*/ {
       |  val <<f>>/*variable,readonly*/ = <<Foo>>/*class*/(1,2)
       |}
       |""".stripMargin,
  )

}
