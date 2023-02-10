package tests.tokens

import tests.BaseSemanticTokensSuite

class SemanticTokensScala3Suite extends BaseSemanticTokensSuite {
  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala2
  )

  check(
    "enum",
    s"""|<<package>>/*keyword*/ <<example>>/*namespace*/
        |
        |<<enum>>/*keyword*/ <<FooEnum>>/*enum,abstract*/:
        |  <<case>>/*keyword*/ <<Bar>>/*enum*/, <<Baz>>/*enum*/
        |<<object>>/*keyword*/ <<FooEnum>>/*class*/
        |""".stripMargin,
  )

  check(
    "enum1",
    s"""|<<package>>/*keyword*/ <<example>>/*namespace*/
        |
        |<<enum>>/*keyword*/ <<FooEnum>>/*enum,abstract*/:
        |  <<case>>/*keyword*/ <<A>>/*enum*/(<<a>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
        |  <<case>>/*keyword*/ <<B>>/*enum*/(<<a>>/*variable,readonly*/: <<Int>>/*class,abstract*/, <<b>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
        |  <<case>>/*keyword*/ <<C>>/*enum*/(<<a>>/*variable,readonly*/: <<Int>>/*class,abstract*/, <<b>>/*variable,readonly*/: <<Int>>/*class,abstract*/, <<c>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
        |
        |""".stripMargin,
  )

  // Issue: Sequential parameters are not highlighted
  // https://github.com/scalameta/metals/issues/4985
  check(
    "named-arguments",
    s"""|<<package>>/*keyword*/ <<example>>/*namespace*/
        |
        |<<def>>/*keyword*/ <<m>>/*method*/(<<xs>>/*parameter*/: <<Int>>/*class,abstract*/*) = <<xs>>/*parameter*/.<<map>>/*method*/(<<_>>/*variable*/ <<+>>/*method*/ <<1>>/*number*/)
        |<<val>>/*keyword*/ <<a>>/*variable,readonly*/ = <<m>>/*method*/(xs = <<1>>/*number*/,<<2>>/*number*/,<<3>>/*number*/)
        |""".stripMargin,
  )

  // Issue: Structural types are not highlighted
  // https://github.com/scalameta/metals/issues/4984
  check(
    "structural-types",
    s"""|<<package>>/*keyword*/ <<example>>/*namespace*/
        |
        |<<import>>/*keyword*/ <<reflect>>/*namespace*/.<<Selectable>>/*class*/.<<reflectiveSelectable>>/*method*/
        |
        |<<object>>/*keyword*/ <<StructuralTypes>>/*class*/:
        |  <<type>>/*keyword*/ <<User>>/*type*/ = {
        |    <<def>>/*keyword*/ <<name>>/*method*/: <<String>>/*type*/
        |    <<def>>/*keyword*/ <<age>>/*method*/: <<Int>>/*class,abstract*/
        |  }
        |
        |  <<val>>/*keyword*/ <<user>>/*variable,readonly*/ = <<null>>/*keyword*/.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
        |  <<user>>/*variable,readonly*/.name
        |  <<user>>/*variable,readonly*/.age
        |
        |  <<val>>/*keyword*/ <<V>>/*variable,readonly*/: <<Object>>/*class*/ {
        |    <<def>>/*keyword*/ <<scalameta>>/*method*/: <<String>>/*type*/
        |  } = <<new>>/*keyword*/:
        |    <<def>>/*keyword*/ <<scalameta>>/*method*/ = <<"4.0">>/*string*/
        |  <<V>>/*variable,readonly*/.scalameta
        |<<end>>/*keyword*/ StructuralTypes
        |""".stripMargin,
  )

  check(
    "vars",
    s"""|<<package>>/*keyword*/ <<example>>/*namespace*/
        |
        |<<object>>/*keyword*/ <<A>>/*class*/ {
        |  <<val>>/*keyword*/ <<a>>/*variable,readonly*/ = <<1>>/*number*/
        |  <<var>>/*keyword*/ <<b>>/*variable*/ = <<2>>/*number*/
        |  <<val>>/*keyword*/ <<c>>/*variable,readonly*/ = <<List>>/*variable,readonly*/(<<1>>/*number*/,<<a>>/*variable,readonly*/,<<b>>/*variable*/)
        |  <<b>>/*variable*/ = <<a>>/*variable,readonly*/
        |""".stripMargin,
  )

}
