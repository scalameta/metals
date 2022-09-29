package tests

import munit.TestOptions

/**
 * Test for request "textDocument/semanticTokens/full"
 * https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 */
class SemanticHighlightLspSuite extends BaseLspSuite("SemanticHighlight") {

  check(
    "class, object, var, val(readonly), method, type, parameter, String(single-line)",
    s"""|<<class>>/*keyword*/  <<Test>>/*class*/{
        |
        | <<var>>/*keyword*/ <<wkStr>>/*variable*/ = <<"Dog-">>/*string*/
        | <<val>>/*keyword*/ <<nameStr>>/*variable,readonly*/ = <<"Jack">>/*string*/
        |
        | <<def>>/*keyword*/ <<Main>>/*method*/={
        |
        |  <<val>>/*keyword*/ <<preStr>>/*variable,readonly*/= <<"I am ">>/*string*/
        |  <<var>>/*keyword*/ <<postStr>>/*variable*/= <<"in a house. ">>/*string*/
        |  <<wkStr>>/*variable*/=<<nameStr>>/*variable,readonly*/ <<+>>/*method*/ <<"Cat-">>/*string*/
        |
        |  <<testC>>/*class*/.<<bc>>/*method*/(<<preStr>>/*variable,readonly*/
        |    <<+>>/*method*/ <<wkStr>>/*variable*/
        |    <<+>>/*method*/ <<preStr>>/*variable,readonly*/)
        | }
        |}
        |
        |<<object>>/*keyword*/  <<testC>>/*class*/{
        |
        | <<def>>/*keyword*/ <<bc>>/*method*/(<<msg>>/*parameter*/:<<String>>/*type*/)={
        |   <<println>>/*method*/(<<msg>>/*parameter*/)
        | }
        |}
        |""".stripMargin,
  )

  check(
    "Comment(Single-Line, Multi-Line)",
    s"""|
        |<<object>>/*keyword*/ <<Main>>/*class*/{
        |
        |   <</**>>/*comment*/
        |<<   * Test of Comment Block>>/*comment*/
        |<<   */>>/*comment*/  <<val>>/*keyword*/ <<x>>/*variable,readonly*/ = <<1>>/*number*/
        |
        |  <<def>>/*keyword*/ <<add>>/*method*/(<<a>>/*parameter*/ : <<Int>>/*class,abstract*/) = {
        |    <<// Single Line Comment>>/*comment*/
        |    <<a>>/*parameter*/ <<+>>/*method,abstract*/ <<1>>/*number*/ <<// com = 1>>/*comment*/
        |   }
        |}
        |
        |
        |""".stripMargin,
  )

  check(
    "number literal, Static",
    s"""|
        |<<object>>/*keyword*/ <<ab>>/*class*/ {
        |  <<var>>/*keyword*/  <<iVar>>/*variable*/:<<Int>>/*class,abstract*/ = <<1>>/*number*/
        |  <<val>>/*keyword*/  <<iVal>>/*variable,readonly*/:<<Double>>/*class,abstract*/ = <<4.94065645841246544e-324d>>/*number*/
        |  <<val>>/*keyword*/  <<fVal>>/*variable,readonly*/:<<Float>>/*class,abstract*/ = <<1.40129846432481707e-45>>/*number*/
        |  <<val>>/*keyword*/  <<lVal>>/*variable,readonly*/:<<Long>>/*class,abstract*/ = <<9223372036854775807L>>/*number*/
        |}
        |
        |<<object>>/*keyword*/ <<sample10>>/*class*/ {
        |  <<def>>/*keyword*/ <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |    <<println>>/*method*/(
        |     (<<ab>>/*class*/.<<iVar>>/*variable*/ <<+>>/*method,abstract*/ <<ab>>/*class*/.<<iVal>>/*variable,readonly*/).<<toString>>/*method*/
        |    )
        |  }
        |}
        |""".stripMargin,
  )

  check(
    "abstract(modifier), trait, type parameter",
    s"""|
        |<<package>>/*keyword*/ a.b
        |<<object>>/*keyword*/ <<Sample5>>/*class*/ {
        |
        |  <<def>>/*keyword*/ <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[<<String>>/*type*/]) ={
        |      <<val>>/*keyword*/ <<itr>>/*variable,readonly*/ = <<new>>/*keyword*/ <<IntIterator>>/*class*/(<<5>>/*number*/)
        |      <<var>>/*keyword*/ <<str>>/*variable*/ = <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/ <<+>>/*method*/ <<",">>/*string*/
        |          <<str>>/*variable*/ += <<itr>>/*variable,readonly*/.<<next>>/*method*/().<<toString>>/*method*/
        |      <<println>>/*method*/(<<"count:">>/*string*/<<+>>/*method*/<<str>>/*variable*/)
        |  }
        |
        |  <<trait>>/*keyword*/ <<Iterator>>/*interface,abstract*/[<<A>>/*typeParameter,abstract*/] {
        |    <<def>>/*keyword*/ <<next>>/*method,abstract*/(): <<A>>/*typeParameter,abstract*/
        |  }
        |
        |  <<abstract>>/*modifier*/ <<class>>/*keyword*/ <<hasLogger>>/*class,abstract*/ {
        |    <<def>>/*keyword*/ <<log>>/*method*/(<<str>>/*parameter*/:<<String>>/*type*/) = {<<println>>/*method*/(<<str>>/*parameter*/)}
        |  }
        |
        |  <<class>>/*keyword*/ <<IntIterator>>/*class*/(<<to>>/*parameter*/: <<Int>>/*class,abstract*/)
        |  <<extends>>/*keyword*/ <<hasLogger>>/*class,abstract*/ <<with>>/*keyword*/ <<Iterator>>/*interface,abstract*/[Int]  {
        |    <<private>>/*modifier*/ <<var>>/*keyword*/ <<current>>/*variable*/ = <<0>>/*number*/
        |    <<override>>/*modifier*/ <<def>>/*keyword*/ <<next>>/*method*/(): <<Int>>/*class,abstract*/ = {
        |      <<if>>/*keyword*/ (<<current>>/*variable*/ <<<>>/*method,abstract*/ <<to>>/*variable,readonly*/) {
        |        <<log>>/*method*/(<<"main">>/*string*/)
        |        <<val>>/*keyword*/ <<t>>/*variable,readonly*/ = <<current>>/*variable*/
        |        <<current>>/*variable*/ = <<current>>/*variable*/ <<+>>/*method,abstract*/ <<1>>/*number*/
        |        <<t>>/*variable,readonly*/
        |      } <<else>>/*keyword*/ <<0>>/*number*/
        |    }
        |  }
        |}
        |
        |
        |""".stripMargin,
  )

  // code is in referred to https://www.scala-lang.org/api/2.13.3/scala/deprecated.html
  check(
    "Deprecated",
    s"""|<<object>>/*keyword*/ <<sample9>>/*class*/ {
        |  <<@>>/*keyword*/<<deprecated>>/*class*/(<<"this method will be removed">>/*string*/, <<"FooLib 12.0">>/*string*/)
        |  <<def>>/*keyword*/ <<oldMethod>>/*method,deprecated*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/) = <<x>>/*parameter*/
        |
        |  <<def>>/*keyword*/ <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*class*/[String]) ={
        |    <<val>>/*keyword*/ <<str>>/*variable,readonly*/ = <<oldMethod>>/*method,deprecated*/(<<2>>/*number*/).<<toString>>/*method*/
        |     <<println>>/*method*/(<<"Hello, world!">>/*string*/<<+>>/*method*/ <<str>>/*variable,readonly*/)
        |  }
        |}
        |""".stripMargin,
  )

  check(
    "import(Out of File)",
    s"""|
        |<<import>>/*keyword*/ scala.math.<<sqrt>>/*method*/
        |<<object>>/*keyword*/ <<sample3>>/*class*/ {
        |
        |  <<def>>/*keyword*/ <<sqrtplus1>>/*method*/(<<x>>/*parameter*/: <<Int>>/*class,abstract*/)
        |     = <<sqrt>>/*method*/(<<x>>/*parameter*/).<<toString>>/*method*/()
        |
        |  <<def>>/*keyword*/ <<main>>/*method*/(<<args>>/*parameter*/: <<Array>>/*method*/[<<String>>/*method*/]) ={
        |    <<println>>/*method*/(<<"Hello, world! : ">>/*string*/ <<+>>/*method*/ <<sqrtplus1>>/*method*/(<<2>>/*number*/))
        |  }
        |}
        |
        |""".stripMargin,
  )

  // check(
  //   "String, Char",
  //   s"""|
  //       |object sample7 {
  //       |  def main(args: Array[String]) ={
  //       |
  //       |    val testStr1 : String = " Hello  "
  //       |    println(testStr1)
  //       |
  //       |    val testStr2 = """This is
  //       |    a multiline
  //       |    Test"""
  //       |    println(testStr2)
  //       |
  //       |    var testChar1 : Char =  'x'
  //       |     println(testChar1.toString())
  //       |
  //       |
  //       |  }
  //       |}
  //       |""".stripMargin
  // )

  // check(
  //   "enum",
  //   s"""|
  //       |
  //       |
  //       |
  //       |""".stripMargin
  // )

  // check(
  //   "Literal Identifer",
  //   s"""|
  //       |
  //       |
  //       |
  //       |""".stripMargin
  // )

  // check(
  //   "Template",
  //   s"""|
  //       |
  //       |
  //       |
  //       |""".stripMargin
  // )

  def check(
      name: TestOptions,
      expected: String,
  ): Unit = {
    val fileContent =
      expected.replaceAll(raw"/\*[\w,]+\*/", "").replaceAll(raw"\<\<|\>\>", "")

    val fileName = "/a/src/main/scala/a/Main.scala"

    test(name) {
      for {
        // potentially we could derive input from
        _ <- initialize(
          s"""/metals.json
             |{"a":{}}
             |${fileName.trim()}
             |${fileContent}
             |""".stripMargin,
          expectError = true,
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ = assertEmpty(client.workspaceDiagnostics)
        _ <- server.assertSemanticHighlight(
          "a/src/main/scala/a/Main.scala",
          expected,
          fileContent,
        )
      } yield ()
    }
  }

}
