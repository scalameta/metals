package tests

import munit.TestOptions

/**
 * Test for request "textDocument/semanticTokens/full"
 * https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 */
class SemanticHighlightLspSuite extends BaseLspSuite("SemanticHighlight") {

  check(
    "empty-file",
    s"""|
        |""".stripMargin,
  )

  check(
    "comments",
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
    "enum-true-false",
    s"""|
        |<<package>>/*keyword*/ <<example>>/*namespace*/
        |<<import>>/*keyword*/ <<java>>/*namespace*/.<<nio>>/*namespace*/.<<file>>/*namespace*/.<<AccessMode>>/*enum*/
        |<<import>>/*keyword*/ <<java>>/*namespace*/.<<nio>>/*namespace*/.<<file>>/*namespace*/.<<AccessMode>>/*enum*/.<<READ>>/*enumMember*/
        |<<import>>/*keyword*/ <<java>>/*namespace*/.<<nio>>/*namespace*/.<<file>>/*namespace*/.<<AccessMode>>/*enum*/.<<WRITE>>/*enumMember*/
        |<<import>>/*keyword*/ <<java>>/*namespace*/.<<nio>>/*namespace*/.<<file>>/*namespace*/.<<AccessMode>>/*enum*/.<<EXECUTE>>/*enumMember*/
        |<<object>>/*keyword*/ <<Main>>/*class*/ {
        |  <<val>>/*keyword*/ <<vTrue>>/*variable,readonly*/ = <<true>>/*keyword*/
        |  <<val>>/*keyword*/ <<vFalse>>/*variable,readonly*/ = <<false>>/*keyword*/
        |  (<<null>>/*keyword*/: <<AccessMode>>/*enumMember,abstract*/) <<match>>/*keyword*/ {
        |    <<case>>/*keyword*/ <<READ>>/*enumMember*/ <<=>>>/*operator*/ <<0>>/*number*/
        |    <<case>>/*keyword*/ <<WRITE>>/*enumMember*/ <<=>>>/*operator*/
        |    <<case>>/*keyword*/ <<EXECUTE>>/*enumMember*/ <<=>>>/*operator*/
        |  }
        |}
        |""".stripMargin,
  )

  def check(
      name: TestOptions,
      expected: String,
      fileName: String = "Main.scala",
  ): Unit = {
    val fileContent =
      TestSemanticTokens.removeSemanticHighlightDecorations(expected)

    val filePath = "a/src/main/scala/a/" + fileName
    val absFilePath = "/" + filePath

    test(name) {
      for {
        // potentially we could derive input from
        _ <- initialize(
          s"""/metals.json
             |{"a":{}}
             |${absFilePath.trim()}
             |${fileContent}
             |""".stripMargin,
          expectError = true,
        )
        _ <- server.didChangeConfiguration(
          """{
            |  "enable-semantic-highlighting": true
            |}
            |""".stripMargin
        )
        _ <- server.didOpen(filePath)
        _ <- server.assertSemanticHighlight(
          filePath,
          expected,
          fileContent,
        )
      } yield ()
    }
  }

}
