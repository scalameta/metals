package tests

import munit.Location
import munit.TestOptions

/**
 * Test for request "textDocument/semanticTokens/full"
 * https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 */
class SemanticHighlightLspSuite extends BaseLspSuite("SemanticHighlight") {

  check(
    "Basic",
    s"""|
        |<<class>>/*keyword*/  <<Test>>/*class*/{
        |
        | <<var>>/*keyword*/ <<wkStr>>/*variable*/ = "Dog-"
        | <<val>>/*keyword*/ <<nameStr>>/*variable,readonly*/ = "Jack"
        |
        | <<def>>/*keyword*/ <<Main>>/*method*/={
        |
        |  <<val>>/*keyword*/ <<preStr>>/*variable,readonly*/= "I am "
        |  <<var>>/*keyword*/ <<postStr>>/*variable*/= "in a house. "
        |  <<wkStr>>/*variable*/="Cat-"
        |
        |  <<testC>>/*class*/.<<bc>>/*method*/(<<preStr>>/*variable,readonly*/ <<+>>/*method*/ <<wkStr>>/*variable*/ <<+>>/*method*/ <<preStr>>/*variable,readonly*/)
        | }
        |}
        |
        |<<object>>/*keyword*/  <<testC>>/*class*/{
        | // Single Line Comment
        | <<def>>/*keyword*/ <<bc>>/*method*/(<<msg>>/*parameter*/:<<String>>/*type*/)={
        |   <<println>>/*method*/(<<msg>>/*parameter*/)
        | }
        |}
        |
        |""".stripMargin
  )

  // check(
  //   "Object,Method",
  //   // note(@tgodzik) Looking at the token types it seems we don't need to include braces etc.
  //   s"""|<<object>>/*keyword*/ <<Main>>/*class*/{
  //       |  <<def>>/*keyword*/ <<add>>/*method*/(<<a>>/*parameter*/ : <<Int>>/*type*/) = {
  //       |    <<a>>/*parameter*/ + 1
  //       |   }
  //       |}""".stripMargin
  // )

  // check(
  //   "Modifiers",
  //   s"""|<<package>>/*keyword*/ scala.meta.internal.pc
  //       |
  //       |<<abstract>>/*keyword*/ <<class>>/*keyword*/ <<Pet>>/*class,abstract*/ (<<name>>/*variable,readonly*/: String) {
  //       |    <<def>>/*keyword*/ <<speak>>/*method*/: <<Unit>>/*abstract*/ = println(s"My name is $$name")
  //       |}
  //       |
  //       |<<final>>/*keyword*/ <<class>>/*keyword*/ <<Dog>>/*class*/(<<name>>/*variable,readonly*/: <<String>>>>/*class*/) <<extends>>/*keyword*/ <<Pet>>/*abstract*/(<<name>>/*parameter*/)
  //       |<<final>>/*keyword*/ <<abstract>>/*keyword*/ <<class>>/*keyword*/ <<Cat>>/*class,abstract*/(<<name>>/*variable,readonly*/: <<String>>>>/*class*/) 
  //       | <<extends>>/*keyword*/ <<Pet>>/*abstract*/(<<name>>/*parameter*/)
  //       |
  //       |<<object>>/*keyword*/ <<Main>>/*class*/{
  //       |  <<val>>/*keyword*/ <<d>>/*variable,readonly*/ = <<new>>/*keyword*/ Dog("Fido") // Declaration
  //       |  <<d>>/*variable,readonly*/.<<speak>>/*method*/
  //       |  <<val>>/*keyword*/ <<c>>/*variable,readonly*/ = <<new>>/*keyword*/ Dog("Mike") // Declaration
  //       |}
  //       |
  //       |""".stripMargin
  // )

  def check(
      name: TestOptions,
      expected: String
  ) = {
    val fileContent =
      expected.replaceAll(raw"/\*[\w,]+\*/", "").replaceAll(raw"\<\<|\>\>", "")
    test(name) {
      for {
        // potentially we could derive input from
        _ <- initialize(
          s"""/metals.json
             |{"a":{}}
             |/a/src/main/scala/a/Main.scala
             |${fileContent}
             |""".stripMargin,
          expectError = true
        )
        _ <- server.didOpen("a/src/main/scala/a/Main.scala")
        _ <- server.assertSemanticHighlight(
          "a/src/main/scala/a/Main.scala",
          expected,
          fileContent
        )
      } yield ()
    }
  }

}
