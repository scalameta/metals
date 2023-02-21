package tests.tokens

import tests.BaseSemanticTokensSuite

class SemanticTokensSuite extends BaseSemanticTokensSuite {

  check(
    "class, object, var, val(readonly), method, type, parameter, String(single-line)",
    s"""|package <<example>>/*namespace*/
        |
        |class <<Test>>/*class*/{
        |
        | var <<wkStr>>/*variable*/ = "Dog-"
        | val <<nameStr>>/*variable,readonly*/ = "Jack"
        |
        | def <<Main>>/*method*/={
        |
        |  val <<preStr>>/*variable,readonly*/= "I am "
        |  var <<postStr>>/*variable*/= "in a house. "
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
        | def <<bc>>/*method*/(<<msg>>/*parameter*/:<<String>>/*type*/)={
        |   <<println>>/*method*/(<<msg>>/*parameter*/)
        | }
        |}
        |""".stripMargin,
  )

  check(
    "Comment(Single-Line, Multi-Line)",
    s"""|package <<example>>/*namespace*/
        |
        |object <<Main>>/*class*/{
        |
        |   /**
        |   * Test of Comment Block
        |   */  val <<x>>/*variable,readonly*/ = 1
        |
        |  def <<add>>/*method*/(<<a>>/*parameter*/ : <<Int>>/*class,abstract*/) = {
        |    // Single Line Comment
        |    <<a>>/*parameter*/ <<+>>/*method,abstract*/ 1 // com = 1
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
            |   */  val <<x>>/*variable,readonly*/ = 1
            |
            |  def <<add>>/*method*/(<<a>>/*parameter*/ : <<Int>>/*class,abstract*/) = {
            |    // Single Line Comment
            |    <<a>>/*parameter*/ <<+>>/*method*/ 1 // com = 1
            |   }
            |}
            |""".stripMargin
    ),
  )

  check(
    "number literal, Static",
    s"""|package <<example>>/*namespace*/
        |
        |object <<ab>>/*class*/ {
        |  var  <<iVar>>/*variable*/:<<Int>>/*class,abstract*/ = 1
        |  val  <<iVal>>/*variable,readonly*/:<<Double>>/*class,abstract*/ = 4.94065645841246544e-324d
        |  val  <<fVal>>/*variable,readonly*/:<<Float>>/*class,abstract*/ = 1.40129846432481707e-45
        |  val  <<lVal>>/*variable,readonly*/:<<Long>>/*class,abstract*/ = 9223372036854775807L
        |}
        |
        |object <<sample10>>/*class*/ {
        |  def <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
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
            |  var  <<iVar>>/*variable*/:<<Int>>/*class,abstract*/ = 1
            |  val  <<iVal>>/*variable,readonly*/:<<Double>>/*class,abstract*/ = 4.94065645841246544e-324d
            |  val  <<fVal>>/*variable,readonly*/:<<Float>>/*class,abstract*/ = 1.40129846432481707e-45
            |  val  <<lVal>>/*variable,readonly*/:<<Long>>/*class,abstract*/ = 9223372036854775807L
            |}
            |
            |object <<sample10>>/*class*/ {
            |  def <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
            |    <<println>>/*method*/(
            |     (<<ab>>/*class*/.<<iVar>>/*variable*/ <<+>>/*method*/ <<ab>>/*class*/.<<iVal>>/*variable,readonly*/).<<toString>>/*method*/
            |    )
            |  }
            |}
            |""".stripMargin
    ),
  )

  check(
    "abstract(modifier), trait, type parameter",
    s"""|
        |package <<a>>/*namespace*/.<<b>>/*namespace*/
        |object <<Sample5>>/*class*/ {
        |
        |  def <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |      val <<itr>>/*variable,readonly*/ = new <<IntIterator>>/*class*/(5)
        |      var <<str>>/*variable*/ = <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/ <<+>>/*method*/ ","
        |          <<str>>/*variable*/ += <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/
        |      <<println>>/*method*/("count:"<<+>>/*method*/<<str>>/*variable*/)
        |  }
        |
        |  trait <<Iterator>>/*interface,abstract*/[<<A>>/*typeParameter,abstract*/] {
        |    def <<next>>/*method,abstract*/(): <<A>>/*typeParameter,abstract*/
        |  }
        |
        |  abstract class <<hasLogger>>/*class,abstract*/ {
        |    def <<log>>/*method*/(<<str>>/*parameter*/:<<String>>/*type*/) = {<<println>>/*method*/(<<str>>/*parameter*/)}
        |  }
        |
        |  class <<IntIterator>>/*class*/(<<to>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
        |  extends <<hasLogger>>/*class,abstract*/ with <<Iterator>>/*interface,abstract*/[<<Int>>/*class,abstract*/]  {
        |    private var <<current>>/*variable*/ = 0
        |    override def <<next>>/*method*/(): <<Int>>/*class,abstract*/ = {
        |      if (<<current>>/*variable*/ <<<>>/*method,abstract*/ <<to>>/*variable,readonly*/) {
        |        <<log>>/*method*/("main")
        |        val <<t>>/*variable,readonly*/ = <<current>>/*variable*/
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
            |  def <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
            |      val <<itr>>/*variable,readonly*/ = new <<IntIterator>>/*class*/(5)
            |      var <<str>>/*variable*/ = <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/ <<+>>/*method*/ ","
            |          <<str>>/*variable*/ += <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/
            |      <<println>>/*method*/("count:"<<+>>/*method*/<<str>>/*variable*/)
            |  }
            |
            |  trait <<Iterator>>/*interface,abstract*/[<<A>>/*typeParameter,abstract*/] {
            |    def <<next>>/*method*/(): <<A>>/*typeParameter,abstract*/
            |  }
            |
            |  abstract class <<hasLogger>>/*class,abstract*/ {
            |    def <<log>>/*method*/(<<str>>/*parameter*/:<<String>>/*type*/) = {<<println>>/*method*/(<<str>>/*parameter*/)}
            |  }
            |
            |  class <<IntIterator>>/*class*/(<<to>>/*variable,readonly*/: <<Int>>/*class,abstract*/)
            |  extends <<hasLogger>>/*class,abstract*/ with <<Iterator>>/*interface,abstract*/[<<Int>>/*class,abstract*/]  {
            |    private var <<current>>/*variable*/ = 0
            |    override def <<next>>/*method*/(): <<Int>>/*class,abstract*/ = {
            |      if (<<current>>/*variable*/ <<<>>/*method*/ <<to>>/*variable,readonly*/) {
            |        <<log>>/*method*/("main")
            |        val <<t>>/*variable,readonly*/ = <<current>>/*variable*/
            |        <<current>>/*variable*/ = <<current>>/*variable*/ <<+>>/*method*/ 1
            |        <<t>>/*variable,readonly*/
            |      } else 0
            |    }
            |  }
            |}
            |
            |
            |""".stripMargin
    ),
  )

  check(
    "deprecated",
    s"""|package <<example>>/*namespace*/
        |object <<sample9>>/*class*/ {
        |  @<<deprecated>>/*class*/("this method will be removed", "FooLib 12.0")
        |  def <<oldMethod>>/*method,deprecated*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/) = <<x>>/*parameter*/
        |
        |  def <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |    val <<str>>/*variable,readonly*/ = <<oldMethod>>/*method,deprecated*/(2).<<toString>>/*method*/
        |     <<println>>/*method*/("Hello, world!"<<+>>/*method*/ <<str>>/*variable,readonly*/)
        |  }
        |}
        |""".stripMargin,
  )
  check(
    "import(Out of File)",
    s"""|package <<example>>/*namespace*/
        |
        |import <<scala>>/*namespace*/.<<collection>>/*namespace*/.<<immutable>>/*namespace*/.<<SortedSet>>/*class*/
        |
        |object <<sample3>>/*class*/ {
        |
        |  def <<sorted1>>/*method*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/)
        |     = <<SortedSet>>/*class*/(<<x>>/*parameter*/)
        |}
        |
        |""".stripMargin,
  )

  check(
    "anonymous-class",
    s"""|package <<example>>/*namespace*/ 
        |object <<A>>/*class*/ {
        |  trait <<Methodable>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/] {
        |    def <<method>>/*method,abstract*/(<<asf>>/*parameter*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/
        |  }
        |
        |  abstract class <<Alp>>/*class,abstract*/(<<alp>>/*variable,readonly*/: <<Int>>/*class,abstract*/) extends <<Methodable>>/*interface,abstract*/[<<String>>/*type*/] {
        |    def <<method>>/*method*/(<<adf>>/*parameter*/: <<String>>/*type*/) = 123
        |  }
        |  val <<a>>/*variable,readonly*/ = new <<Alp>>/*class,abstract*/(<<alp>>/*parameter*/ = 10) {
        |    override def <<method>>/*method*/(<<adf>>/*parameter*/: <<String>>/*type*/): <<Int>>/*class,abstract*/ = 321
        |  }
        |}""".stripMargin,
    // In Scala 3 methods in `trait` are not abstract
    compat = Map(
      "3" -> s"""|package <<example>>/*namespace*/ 
                 |object <<A>>/*class*/ {
                 |  trait <<Methodable>>/*interface,abstract*/[<<T>>/*typeParameter,abstract*/] {
                 |    def <<method>>/*method*/(<<asf>>/*parameter*/: <<T>>/*typeParameter,abstract*/): <<Int>>/*class,abstract*/
                 |  }
                 |
                 |  abstract class <<Alp>>/*class,abstract*/(<<alp>>/*variable,readonly*/: <<Int>>/*class,abstract*/) extends <<Methodable>>/*interface,abstract*/[<<String>>/*type*/] {
                 |    def <<method>>/*method*/(<<adf>>/*parameter*/: <<String>>/*type*/) = 123
                 |  }
                 |  val <<a>>/*variable,readonly*/ = new <<Alp>>/*class,abstract*/(<<alp>>/*parameter*/ = 10) {
                 |    override def <<method>>/*method*/(<<adf>>/*parameter*/: <<String>>/*type*/): <<Int>>/*class,abstract*/ = 321
                 |  }
                 |}""".stripMargin
    ),
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
        |}""".stripMargin,
  )

}
