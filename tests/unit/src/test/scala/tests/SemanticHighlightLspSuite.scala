package tests

import munit.TestOptions

/**
 * Test for request "textDocument/semanticTokens/full"
 * https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 */
class SemanticHighlightLspSuite extends BaseLspSuite("SemanticHighlight") {

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
    "Enum",
    s"""|
        |<<package>>/*keyword*/ <<example>>/*namespace*/
        |<<import>>/*keyword*/ java.nio.file.<<AccessMode>>/*enum*/
        |<<import>>/*keyword*/ java.nio.file.AccessMode.<<READ>>/*enumMember*/
        |<<import>>/*keyword*/ java.nio.file.AccessMode.<<WRITE>>/*enumMember*/
        |<<import>>/*keyword*/ java.nio.file.AccessMode.<<EXECUTE>>/*enumMember*/
        |<<object>>/*keyword*/ <<Main>>/*class*/ {
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
        // _ = assertEmpty(client.workspaceDiagnostics)
        _ <- server.assertSemanticHighlight(
          "a/src/main/scala/a/Main.scala",
          expected,
          fileContent,
        )
      } yield ()
    }
  }

}
