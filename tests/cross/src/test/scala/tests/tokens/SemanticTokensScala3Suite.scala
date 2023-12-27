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
       |""".stripMargin
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
       |""".stripMargin
  )

  // Issue: Sequential parameters are not highlighted
  // https://github.com/scalameta/metals/issues/4985
  check(
    "named-arguments",
    s"""|package <<example>>/*namespace*/
        |
        |def <<m>>/*method,definition*/(<<xs>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/*) = <<xs>>/*parameter,readonly*/.<<map>>/*method*/(<<_>>/*parameter,readonly*/ <<+>>/*method*/ 1)
        |val <<a>>/*variable,definition,readonly*/ = <<m>>/*method*/(xs = 1,2,3)
        |""".stripMargin
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
            |  type <<User>>/*type,definition*/ = {
            |    def <<name>>/*method,declaration*/: <<String>>/*type*/
            |    def <<age>>/*method,declaration*/: <<Int>>/*class,abstract*/
            |  }
            |
            |  val <<user>>/*variable,definition,readonly*/ = null.<<asInstanceOf>>/*method*/[<<User>>/*type*/]
            |  <<user>>/*variable,readonly*/.<<name>>/*method*/
            |  <<user>>/*variable,readonly*/.<<age>>/*method*/
            |
            |  val <<V>>/*variable,definition,readonly*/: <<Object>>/*class*/ {
            |    def <<scalameta>>/*method,declaration*/: <<String>>/*type*/
            |  } = new:
            |    def <<scalameta>>/*method,definition*/ = "4.0"
            |  <<V>>/*variable,readonly*/.<<scalameta>>/*method*/
            |end StructuralTypes
            |""".stripMargin
    )
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
        |""".stripMargin
  )

  check(
    "predef",
    """
      |object <<Main>>/*class*/ {
      |  val <<a>>/*variable,definition,readonly*/ = <<List>>/*class*/(1,2,3)
      |  val <<y>>/*variable,definition,readonly*/ = <<Vector>>/*class*/(1,2)
      |  val <<z>>/*variable,definition,readonly*/ = <<Set>>/*class*/(1,2,3)
      |  val <<w>>/*variable,definition,readonly*/ = <<Right>>/*class*/(1)
      |}""".stripMargin
  )

  check(
    "case-class",
    """|case class <<Foo>>/*class*/(<<i>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<j>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |
       |object <<A>>/*class*/ {
       |  val <<f>>/*variable,definition,readonly*/ = <<Foo>>/*class*/(1,2)
       |}
       |""".stripMargin
  )

  check(
    "import-selector",
    """|package <<a>>/*namespace*/
       |
       |import <<a>>/*namespace*/.<<Tag>>/*class*/.<<@@>>/*type*/
       |
       |object <<A>>/*class*/ {
       |  case class <<B>>/*class*/(<<c>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |}
       |
       |object <<Tag>>/*class*/ {
       |  type <<@@>>/*type,definition*/ = <<Int>>/*class,abstract*/
       |}
       |""".stripMargin
  )

  check(
    "main-annot",
    """|@<<main>>/*class*/ def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]): <<Unit>>/*class,abstract*/ = ()
       |""".stripMargin
  )

  check(
    "constructor2",
    """
      |object <<Bar>>/*class*/ {
      |  class <<Abc>>/*class*/[<<T>>/*typeParameter,definition,abstract*/](<<a>>/*variable,declaration,readonly*/: <<T>>/*typeParameter,abstract*/)
      |}
      |
      |object <<O>>/*class*/ {
      |  val <<x>>/*variable,definition,readonly*/ = new <<Bar>>/*class*/.<<Abc>>/*class*/(2)
      |  val <<y>>/*variable,definition,readonly*/ = new <<Bar>>/*class*/.<<Abc>>/*class*/[<<Int>>/*class,abstract*/](2)
      |  val <<z>>/*variable,definition,readonly*/ = <<Bar>>/*class*/.<<Abc>>/*class*/(2)
      |  val <<w>>/*variable,definition,readonly*/ = <<Bar>>/*class*/.<<Abc>>/*class*/[<<Int>>/*class,abstract*/](2)
      |}""".stripMargin
  )

  check(
    "i5977",
    """
      |sealed trait <<ExtensionProvider>>/*interface,abstract*/ {
      |  extension [<<A>>/*typeParameter,definition,abstract*/] (<<self>>/*parameter,declaration,readonly*/: <<A>>/*typeParameter,abstract*/) {
      |    def <<typeArg>>/*method,declaration*/[<<B>>/*typeParameter,definition,abstract*/]: <<B>>/*typeParameter,abstract*/
      |    def <<inferredTypeArg>>/*method,declaration*/[<<C>>/*typeParameter,definition,abstract*/](<<value>>/*parameter,declaration,readonly*/: <<C>>/*typeParameter,abstract*/): <<C>>/*typeParameter,abstract*/
      |}
      |
      |object <<Repro>>/*class*/ {
      |  def <<usage>>/*method,definition*/[<<A>>/*typeParameter,definition,abstract*/](<<f>>/*parameter,declaration,readonly*/: <<ExtensionProvider>>/*interface,abstract*/ ?=> <<A>>/*typeParameter,abstract*/ => <<Any>>/*class,abstract*/): <<Any>>/*class,abstract*/ = <<???>>/*method*/
      |
      |  <<usage>>/*method*/[<<Int>>/*class,abstract*/](<<_>>/*parameter,readonly*/.<<inferredTypeArg>>/*method*/("str"))
      |  <<usage>>/*method*/[<<Int>>/*class,abstract*/](<<_>>/*parameter,readonly*/.<<inferredTypeArg>>/*method*/[<<String>>/*type*/]("str"))
      |  <<usage>>/*method*/[<<Option>>/*class,abstract*/[<<Int>>/*class,abstract*/]](<<_>>/*parameter,readonly*/.<<typeArg>>/*method*/[<<Some>>/*class*/[<<Int>>/*class,abstract*/]].<<value>>/*variable,readonly*/.<<inferredTypeArg>>/*method*/("str"))
      |  <<usage>>/*method*/[<<Option>>/*class,abstract*/[<<Int>>/*class,abstract*/]](<<_>>/*parameter,readonly*/.<<typeArg>>/*method*/[<<Some>>/*class*/[<<Int>>/*class,abstract*/]].<<value>>/*variable,readonly*/.<<inferredTypeArg>>/*method*/[<<String>>/*type*/]("str"))
      |}
      |""".stripMargin
  )

}
