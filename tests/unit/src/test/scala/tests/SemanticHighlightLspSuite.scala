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
    """|<<object>>/*keyword*/ <<Main>>/*class*/{
       |
       |   <</**>>/*comment*/
       |<<   * Test of Comment Block>>/*comment*/
       |<<   */>>/*comment*/  <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/ = <<1>>/*number*/
       |
       |  <<def>>/*keyword*/ <<add>>/*method,definition*/(<<a>>/*parameter,declaration,readonly*/ : <<Int>>/*class,abstract*/) = {
       |    <<// Single Line Comment>>/*comment*/
       |    <<a>>/*parameter,readonly*/ <<+>>/*method,abstract*/ <<1>>/*number*/ <<// com = 1>>/*comment*/
       |   }
       |}
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
        |  <<val>>/*keyword*/ <<vTrue>>/*variable,definition,readonly*/ = <<true>>/*keyword*/
        |  <<val>>/*keyword*/ <<vFalse>>/*variable,definition,readonly*/ = <<false>>/*keyword*/
        |  (<<null>>/*keyword*/: <<AccessMode>>/*enumMember,abstract*/) <<match>>/*keyword*/ {
        |    <<case>>/*keyword*/ <<READ>>/*enumMember*/ <<=>>>/*operator*/ <<0>>/*number*/
        |    <<case>>/*keyword*/ <<WRITE>>/*enumMember*/ <<=>>>/*operator*/
        |    <<case>>/*keyword*/ <<EXECUTE>>/*enumMember*/ <<=>>>/*operator*/
        |  }
        |}
        |""".stripMargin,
  )

  check(
    "multiline-comment",
    """| <</** This is >>/*comment*/
       |<<*  a multiline>>/*comment*/
       |<<*  comment>>/*comment*/
       |<<*/>>/*comment*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {}
       |""".stripMargin,
  )

  check(
    "using-directive",
    """|<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable*/ <<"abc::abc:123">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<scala>>/*variable*/ <<"3.1.1">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<options>>/*variable*/ <<"-Xasync">>/*string*/, <<"-Xfatal-warnings">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<packaging>>/*variable*/.<<provided>>/*variable*/ <<"org.apache.spark::spark-sql">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<target>>/*variable*/.<<platform>>/*variable*/ <<"scala-js">>/*string*/, <<"scala-native">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable*/ <<"io.circe::circe-core:0.14.0">>/*string*/, <<"io.circe::circe-core_native::0.14.0">>/*string*/
       |<<object>>/*keyword*/ <<A>>/*class*/ {}
       |""".stripMargin,
  )

  check(
    "simple",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {
       |  <<case>>/*keyword*/ <<class>>/*keyword*/ <<B>>/*class*/(<<c>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |}
       |""".stripMargin,
  )

  check(
    "predef",
    """|<<object>>/*keyword*/ <<Main>>/*class*/ {
       |  <<val>>/*keyword*/ <<x>>/*variable,definition,readonly*/ = <<List>>/*class*/(<<1>>/*number*/,<<2>>/*number*/,<<3>>/*number*/)
       |  <<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/ = <<a>>/*variable,readonly*/ <<match>>/*keyword*/ {
       |    <<case>>/*keyword*/ <<List>>/*class*/(<<a>>/*variable*/,<<b>>/*variable*/,<<c>>/*variable*/) <<=>>>/*operator*/ <<a>>/*variable,readonly*/
       |    <<case>>/*keyword*/ <<_>>/*variable*/ <<=>>>/*operator*/ <<0>>/*number*/
       |  }
       |  <<val>>/*keyword*/ <<z>>/*variable,definition,readonly*/ = <<Set>>/*class*/(<<1>>/*number*/,<<2>>/*number*/,<<3>>/*number*/)
       |  <<val>>/*keyword*/ <<w>>/*variable,definition,readonly*/ = <<Right>>/*class*/(<<1>>/*number*/)
       |}
       |""".stripMargin,
  )

  check(
    "case-class",
    """|<<case>>/*keyword*/ <<class>>/*keyword*/ <<Foo>>/*class*/(<<i>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/, <<j>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {
       |  <<val>>/*keyword*/ <<f>>/*variable,definition,readonly*/ = <<Foo>>/*class*/(<<1>>/*number*/,<<2>>/*number*/)
       |}
       |""".stripMargin,
  )

  check(
    "mutable",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {
       |  <<var>>/*keyword*/ <<abc>>/*variable,definition*/ = <<123>>/*number*/
       |  <<var>>/*keyword*/ <<edf>>/*variable,definition*/ = <<abc>>/*variable*/ <<+>>/*method,abstract*/ <<2>>/*number*/
       |  <<abc>>/*variable*/ = <<edf>>/*variable*/ <<->>/*method,abstract*/ <<2>>/*number*/
       |  <<A>>/*class*/.<<edf>>/*variable*/ = <<A>>/*class*/.<<abc>>/*variable*/ 
       |
       |  <<def>>/*keyword*/ <<m>>/*method,definition*/() = {
       |    <<var>>/*keyword*/ <<beta>>/*variable,definition*/ = <<3>>/*number*/
       |    <<beta>>/*variable*/ = <<beta>>/*variable*/ <<+>>/*method,abstract*/ <<1>>/*number*/
       |    <<beta>>/*variable*/
       |  }
       |}
       |""".stripMargin,
  )

  def check(
      name: TestOptions,
      expected: String,
      fileName: String = "Main.scala",
  )(implicit loc: munit.Location): Unit = {
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
