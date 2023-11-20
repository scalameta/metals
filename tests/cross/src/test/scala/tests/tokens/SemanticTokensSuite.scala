package tests.tokens

import tests.BaseSemanticTokensSuite

class SemanticTokensSuite extends BaseSemanticTokensSuite {

  check(
    "class, object, var, val(readonly), method, type, parameter, String(single-line)",
    s"""|package <<example>>/*namespace*/
        |
        |class <<Test>>/*class*/{
        |
        | var <<wkStr>>/*variable,definition*/ = "Dog-"
        | val <<nameStr>>/*variable,definition,readonly*/ = "Jack"
        |
        | def <<Main>>/*method,definition*/={
        |
        |  val <<preStr>>/*variable,definition,readonly*/= "I am "
        |  var <<postStr>>/*variable,definition*/= "in a house. "
        |  <<wkStr>>/*variable*/=<<nameStr>>/*variable,readonly*/ <<+>>/*method*/ "Cat-"
        |
        |  <<testC>>/*class*/.<<bc>>/*method*/(<<preStr>>/*variable,readonly*/
        |    <<+>>/*method*/ <<wkStr>>/*variable*/
        |    <<+>>/*method*/ <<preStr>>/*variable,readonly*/)
        | }
        |}
        |
        |object <<testC>>/*class*/{
        |
        | def <<bc>>/*method,definition*/(<<msg>>/*parameter,declaration,readonly*/:<<String>>/*type*/)={
        |   <<println>>/*method*/(<<msg>>/*parameter,readonly*/)
        | }
        |}
        |""".stripMargin
  )

  check(
    "Comment(Single-Line, Multi-Line)",
    s"""|package <<example>>/*namespace*/
        |
        |object <<Main>>/*class*/{
        |
        |   /**
        |   * Test of Comment Block
        |   */  val <<x>>/*variable,definition,readonly*/ = 1
        |
        |  def <<add>>/*method,definition*/(<<a>>/*parameter,declaration,readonly*/ : <<Int>>/*class,abstract*/) = {
        |    // Single Line Comment
        |    <<a>>/*parameter,readonly*/ <<+>>/*method,abstract*/ 1 // com = 1
        |   }
        |}
        |""".stripMargin,
    compat = Map(
      "3" ->
        s"""|package <<example>>/*namespace*/
            |
            |object <<Main>>/*class*/{
            |
            |   /**
            |   * Test of Comment Block
            |   */  val <<x>>/*variable,definition,readonly*/ = 1
            |
            |  def <<add>>/*method,definition*/(<<a>>/*parameter,declaration,readonly*/ : <<Int>>/*class,abstract*/) = {
            |    // Single Line Comment
            |    <<a>>/*parameter,readonly*/ <<+>>/*method*/ 1 // com = 1
            |   }
            |}
            |""".stripMargin
    )
  )

  check(
    "number literal, Static",
    s"""|package <<example>>/*namespace*/
        |
        |object <<ab>>/*class*/ {
        |  var  <<iVar>>/*variable,definition*/:<<Int>>/*class,abstract*/ = 1
        |  val  <<iVal>>/*variable,definition,readonly*/:<<Double>>/*class,abstract*/ = 4.94065645841246544e-324d
        |  val  <<fVal>>/*variable,definition,readonly*/:<<Float>>/*class,abstract*/ = 1.40129846432481707e-45
        |  val  <<lVal>>/*variable,definition,readonly*/:<<Long>>/*class,abstract*/ = 9223372036854775807L
        |}
        |
        |object <<sample10>>/*class*/ {
        |  def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |    <<println>>/*method*/(
        |     (<<ab>>/*class*/.<<iVar>>/*variable*/ <<+>>/*method,abstract*/ <<ab>>/*class*/.<<iVal>>/*variable,readonly*/).<<toString>>/*method*/
        |    )
        |  }
        |}
        |""".stripMargin,
    // In Scala 3 `+` is not abstract
    compat = Map(
      "3" ->
        s"""|package <<example>>/*namespace*/
            |
            |object <<ab>>/*class*/ {
            |  var  <<iVar>>/*variable,definition*/:<<Int>>/*class,abstract*/ = 1
            |  val  <<iVal>>/*variable,definition,readonly*/:<<Double>>/*class,abstract*/ = 4.94065645841246544e-324d
            |  val  <<fVal>>/*variable,definition,readonly*/:<<Float>>/*class,abstract*/ = 1.40129846432481707e-45
            |  val  <<lVal>>/*variable,definition,readonly*/:<<Long>>/*class,abstract*/ = 9223372036854775807L
            |}
            |
            |object <<sample10>>/*class*/ {
            |  def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
            |    <<println>>/*method*/(
            |     (<<ab>>/*class*/.<<iVar>>/*variable*/ <<+>>/*method*/ <<ab>>/*class*/.<<iVal>>/*variable,readonly*/).<<toString>>/*method*/
            |    )
            |  }
            |}
            |""".stripMargin
    )
  )

  check(
    "abstract(modifier), trait, type parameter",
    s"""|
        |package <<a>>/*namespace*/.<<b>>/*namespace*/
        |object <<Sample5>>/*class*/ {
        |
        |  type <<PP>>/*type,definition*/ = <<Int>>/*class,abstract*/
        |  def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |      val <<itr>>/*variable,definition,readonly*/ = new <<IntIterator>>/*class*/(5)
        |      var <<str>>/*variable,definition*/ = <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/ <<+>>/*method*/ ","
        |          <<str>>/*variable*/ += <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/
        |      <<println>>/*method*/("count:"<<+>>/*method*/<<str>>/*variable*/)
        |  }
        |
        |  trait <<Iterator>>/*interface,abstract*/[<<A>>/*typeParameter,declaration,abstract*/] {
        |    def <<next>>/*method,declaration,abstract*/(): <<A>>/*typeParameter,abstract*/
        |  }
        |
        |  abstract class <<hasLogger>>/*class,abstract*/ {
        |    def <<log>>/*method,definition*/(<<str>>/*parameter,declaration,readonly*/:<<String>>/*type*/) = {<<println>>/*method*/(<<str>>/*parameter,readonly*/)}
        |  }
        |
        |  class <<IntIterator>>/*class*/(<<to>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
        |  extends <<hasLogger>>/*class,abstract*/ with <<Iterator>>/*interface,abstract*/[<<Int>>/*class,abstract*/]  {
        |    private var <<current>>/*variable,definition*/ = 0
        |    override def <<next>>/*method,definition*/(): <<Int>>/*class,abstract*/ = {
        |      if (<<current>>/*variable*/ <<<>>/*method,abstract*/ <<to>>/*variable,readonly*/) {
        |        <<log>>/*method*/("main")
        |        val <<t>>/*variable,definition,readonly*/ = <<current>>/*variable*/
        |        <<current>>/*variable*/ = <<current>>/*variable*/ <<+>>/*method,abstract*/ 1
        |        <<t>>/*variable,readonly*/
        |      } else 0
        |    }
        |  }
        |}
        |
        |
        |""".stripMargin,
    // In Scala 3 `+` is not abstract
    compat = Map(
      "3" ->
        s"""|
            |package <<a>>/*namespace*/.<<b>>/*namespace*/
            |object <<Sample5>>/*class*/ {
            |
            |  type <<PP>>/*type,definition*/ = <<Int>>/*class,abstract*/
            |  def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
            |      val <<itr>>/*variable,definition,readonly*/ = new <<IntIterator>>/*class*/(5)
            |      var <<str>>/*variable,definition*/ = <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/ <<+>>/*method*/ ","
            |          <<str>>/*variable*/ += <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/
            |      <<println>>/*method*/("count:"<<+>>/*method*/<<str>>/*variable*/)
            |  }
            |
            |  trait <<Iterator>>/*interface,abstract*/[<<A>>/*typeParameter,definition,abstract*/] {
            |    def <<next>>/*method,declaration*/(): <<A>>/*typeParameter,abstract*/
            |  }
            |
            |  abstract class <<hasLogger>>/*class,abstract*/ {
            |    def <<log>>/*method,definition*/(<<str>>/*parameter,declaration,readonly*/:<<String>>/*type*/) = {<<println>>/*method*/(<<str>>/*parameter,readonly*/)}
            |  }
            |
            |  class <<IntIterator>>/*class*/(<<to>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
            |  extends <<hasLogger>>/*class,abstract*/ with <<Iterator>>/*interface,abstract*/[<<Int>>/*class,abstract*/]  {
            |    private var <<current>>/*variable,definition*/ = 0
            |    override def <<next>>/*method,definition*/(): <<Int>>/*class,abstract*/ = {
            |      if (<<current>>/*variable*/ <<<>>/*method*/ <<to>>/*variable,readonly*/) {
            |        <<log>>/*method*/("main")
            |        val <<t>>/*variable,definition,readonly*/ = <<current>>/*variable*/
            |        <<current>>/*variable*/ = <<current>>/*variable*/ <<+>>/*method*/ 1
            |        <<t>>/*variable,readonly*/
            |      } else 0
            |    }
            |  }
            |}
            |
            |
            |""".stripMargin
    )
  )

  check(
    "deprecated",
    s"""|package <<example>>/*namespace*/
        |object <<sample9>>/*class*/ {
        |  @<<deprecated>>/*class*/("this method will be removed", "FooLib 12.0")
        |  def <<oldMethod>>/*method,definition,deprecated*/(<<x>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/) = <<x>>/*parameter,readonly*/
        |
        |  def <<main>>/*method,definition*/(<<args>>/*parameter,declaration,readonly*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |    val <<str>>/*variable,definition,readonly*/ = <<oldMethod>>/*method,deprecated*/(2).<<toString>>/*method*/
        |     <<println>>/*method*/("Hello, world!"<<+>>/*method*/ <<str>>/*variable,readonly*/)
        |  }
        |}
        |""".stripMargin
  )
  check(
    "import(Out of File)",
    s"""|package <<example>>/*namespace*/
        |
        |import <<scala>>/*namespace*/.<<collection>>/*namespace*/.<<immutable>>/*namespace*/.<<SortedSet>>/*class*/
        |
        |object <<sample3>>/*class*/ {
        |
        |  def <<sorted1>>/*method,definition*/(<<x>>/*parameter,declaration,readonly*/: <<Int>>/*class,abstract*/)
        |     = <<SortedSet>>/*class*/(<<x>>/*parameter,readonly*/)
        |}
        |
        |""".stripMargin
  )

  check(
    "anonymous-class",
    s"""|package <<example>>/*namespace*/ 
        |object <<A>>/*class*/ {
        |  trait <<Methodable>>/*interface,abstract*/[<<T>>/*typeParameter,declaration,abstract*/] {
        |    def <<method>>/*method,declaration,abstract*/(<<asf>>/*parameter,declaration,readonly*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/
        |  }
        |
        |  abstract class <<Alp>>/*class,abstract*/(<<alp>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/) extends <<Methodable>>/*interface,abstract*/[<<String>>/*type*/] {
        |    def <<method>>/*method,definition*/(<<adf>>/*parameter,declaration,readonly*/: <<String>>/*type*/) = 123
        |  }
        |  val <<a>>/*variable,definition,readonly*/ = new <<Alp>>/*class,abstract*/(<<alp>>/*parameter,readonly*/ = 10) {
        |    override def <<method>>/*method,definition*/(<<adf>>/*parameter,declaration,readonly*/: <<String>>/*type*/): <<Int>>/*class,abstract*/ = 321
        |  }
        |}""".stripMargin,
    // In Scala 3 methods in `trait` are not abstract
    compat = Map(
      "3" -> s"""|package <<example>>/*namespace*/ 
                 |object <<A>>/*class*/ {
                 |  trait <<Methodable>>/*interface,abstract*/[<<T>>/*typeParameter,definition,abstract*/] {
                 |    def <<method>>/*method,declaration*/(<<asf>>/*parameter,declaration,readonly*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/
                 |  }
                 |
                 |  abstract class <<Alp>>/*class,abstract*/(<<alp>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/) extends <<Methodable>>/*interface,abstract*/[<<String>>/*type*/] {
                 |    def <<method>>/*method,definition*/(<<adf>>/*parameter,declaration,readonly*/: <<String>>/*type*/) = 123
                 |  }
                 |  val <<a>>/*variable,definition,readonly*/ = new <<Alp>>/*class,abstract*/(<<alp>>/*parameter,readonly*/ = 10) {
                 |    override def <<method>>/*method,definition*/(<<adf>>/*parameter,declaration,readonly*/: <<String>>/*type*/): <<Int>>/*class,abstract*/ = 321
                 |  }
                 |}""".stripMargin
    )
  )

  check(
    "import-rename",
    s"""|package <<example>>/*namespace*/
        |
        |import <<util>>/*namespace*/.{<<Failure>>/*class*/ => <<NoBad>>/*class*/}
        |
        |class <<Imports>>/*class*/ {
        |  // rename reference
        |  <<NoBad>>/*class*/(null)
        |}""".stripMargin
  )

  check(
    "pattern-match",
    s"""|package <<example>>/*namespace*/
        |
        |class <<Imports>>/*class*/ {
        |  
        |  val <<a>>/*variable,definition,readonly*/ = <<Option>>/*class*/(<<Option>>/*class*/(""))
        |  <<a>>/*variable,readonly*/ match {
        |    case <<Some>>/*class*/(<<Some>>/*class*/(<<b>>/*variable,definition,readonly*/)) => <<b>>/*variable,readonly*/
        |    case <<Some>>/*class*/(<<b>>/*variable,definition,readonly*/) => <<b>>/*variable,readonly*/
        |    case <<other>>/*variable,definition,readonly*/ => 
        |  }
        |}""".stripMargin
  )

  check(
    "pattern-match-value",
    s"""|package <<example>>/*namespace*/
        |
        |object <<A>>/*class*/ {
        |  val <<x>>/*variable,definition,readonly*/ = <<List>>/*class*/(1,2,3)
        |  val <<s>>/*variable,definition,readonly*/ = <<Some>>/*class*/(1)
        |  val <<Some>>/*class*/(<<s1>>/*variable,definition,readonly*/) = <<s>>/*variable,readonly*/
        |  val <<Some>>/*class*/(<<s2>>/*variable,definition,readonly*/) = <<s>>/*variable,readonly*/
        |}
        |""".stripMargin
  )

  check(
    "predef",
    """
      |object <<Main>>/*class*/ {
      |  val <<a>>/*variable,definition,readonly*/ = <<List>>/*class*/(1,2,3)
      |  val <<y>>/*class,definition*/ = <<List>>/*class*/
      |  val <<z>>/*class,definition*/ = <<scala>>/*namespace*/.<<collection>>/*namespace*/.<<immutable>>/*namespace*/.<<List>>/*class*/
      |}
      |""".stripMargin
  )

  check(
    "val-object",
    """|case class <<X>>/*class*/(<<a>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |object <<X>>/*class*/
       |
       |object <<Main>>/*class*/ {
       |  val <<x>>/*class,definition*/ = <<X>>/*class*/
       |  val <<y>>/*variable,definition,readonly*/ = <<X>>/*class*/(1)
       |}
       |""".stripMargin
  )

  // When for-comprehension includes line with `=`, we get `scala.x$1`, `scala.x$2` symbols on `foo`.
  // Both `scala` and `x$#` have position on `foo`, and we don't want to highlight it as a `scala` package,
  // so we need `namespace` to have lower priority than `variable`.
  check(
    "for-comprehension",
    s"""|package <<example>>/*namespace*/
        |
        |object <<B>>/*class*/ {
        |  val <<a>>/*variable,definition,readonly*/ = for {
        |    <<foo>>/*variable,definition,readonly*/ <- <<List>>/*class*/("a", "b", "c")
        |    _ = <<println>>/*method*/("print!")
        |  } yield <<foo>>/*variable,readonly*/
        |}
        |""".stripMargin,
    compat = Map(
      "3" ->
        """|package <<example>>/*namespace*/
           |
           |object <<B>>/*class*/ {
           |  val <<a>>/*variable,definition,readonly*/ = for {
           |    <<foo>>/*variable,definition,readonly*/ <- <<List>>/*class*/("a", "b", "c")
           |    <<_>>/*class,abstract*/ = <<println>>/*method*/("print!")
           |  } yield <<foo>>/*variable,readonly*/
           |}
           |""".stripMargin
    )
  )

  check(
    "named-arg-backtick",
    """|object <<Main>>/*class*/ {
       |  def <<foo>>/*method,definition*/(<<`type`>>/*parameter,declaration,readonly*/: <<String>>/*type*/): <<String>>/*type*/ = <<`type`>>/*parameter,readonly*/
       |  val <<x>>/*variable,definition,readonly*/ = <<foo>>/*method*/(
       |    <<`type`>>/*parameter,readonly*/ = "abc"
       |  )
       |}
       |""".stripMargin
  )

}
