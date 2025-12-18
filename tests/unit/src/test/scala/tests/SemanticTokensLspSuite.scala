package tests

import munit.TestOptions

/**
 * Test for request "textDocument/semanticTokens/full"
 * https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_semanticTokens
 */
class SemanticTokensLspSuite extends BaseLspSuite("SemanticTokens") {

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
        |  (<<null>>/*keyword*/: <<AccessMode>>/*enumMember*/) <<match>>/*keyword*/ {
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
    "multiline-comment-with-whitespace",
    """| <</** This is >>/*comment*/
       |<<*  a multiline>>/*comment*/
       |
       |<<*  comment>>/*comment*/
       |
       |<<*/>>/*comment*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {}
       |""".stripMargin,
  )

  check(
    "docstring",
    """|<<object>>/*keyword*/ <<A>>/*class*/ {
       |  <</**>>/*comment*/
       |<<    * Some example Scaladoc>>/*comment*/
       |<<    * >>/*comment*/<<@param>>/*keyword*/<< >>/*comment*/<<xs>>/*variable,readonly*/<< is [[>>/*comment*/<<scala.collection.immutable.List>>/*string*/<<]]>>/*comment*/
       |<<    * >>/*comment*/<<@throws>>/*keyword*/<< >>/*comment*/<<java.util.Exception>>/*class*/<< is [[>>/*comment*/<<scala.collection.immutable.List>>/*string*/<<]]>>/*comment*/
       |<<    * >>/*comment*/<<@return>>/*keyword*/<< the same list>>/*comment*/
       |<<    * >>/*comment*/<<@note>>/*keyword*/<< This is a note [[>>/*comment*/<<java.lang.String>>/*string*/<<]] or [[>>/*comment*/<<scala.collection.Seq>>/*string*/<<]]>>/*comment*/
       |<<    */>>/*comment*/
       |  <<def>>/*keyword*/ <<m>>/*method,definition*/(<<xs>>/*parameter,declaration,readonly*/: <<List>>/*type*/[<<Int>>/*class,abstract*/]): <<List>>/*type*/[<<Int>>/*class,abstract*/] = <<xs>>/*parameter,readonly*/
       |}
       |""".stripMargin,
  )

  check(
    "using-directive",
    """|<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable,readonly*/ <<"abc::abc:123">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<scala>>/*variable,readonly*/ <<"3.1.1">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<options>>/*variable,readonly*/ <<"-Xasync">>/*string*/, <<"-Xfatal-warnings">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<packaging.provided>>/*variable,readonly*/ <<"org.apache.spark::spark-sql">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<target.platform>>/*variable,readonly*/ <<"scala-js">>/*string*/, <<"scala-native">>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable,readonly*/ <<"io.circe::circe-core:0.14.0">>/*string*/, <<"io.circe::circe-core_native::0.14.0">>/*string*/
       |<<object>>/*keyword*/ <<A>>/*class*/ {}
       |""".stripMargin,
  )

  check(
    "using-directives-no-quotes",
    """|<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable,readonly*/ <<abc::abc:123>>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<scala>>/*variable,readonly*/ <<3.1.1>>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<options>>/*variable,readonly*/ <<-Xasync>>/*string*/, <<-Xfatal-warnings>>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<packaging.provided>>/*variable,readonly*/ <<org.apache.spark::spark-sql>>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<target.platform>>/*variable,readonly*/ <<scala-js>>/*string*/, <<scala-native>>/*string*/
       |<<//>>>/*comment*/ <<using>>/*keyword*/ <<lib>>/*variable,readonly*/ <<io.circe::circe-core:0.14.0>>/*string*/, <<io.circe::circe-core_native::0.14.0>>/*string*/
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
       |  <<val>>/*keyword*/ <<y>>/*variable,definition,readonly*/ = a <<match>>/*keyword*/ {
       |    <<case>>/*keyword*/ <<List>>/*class*/(a,b,c) <<=>>>/*operator*/ a
       |    <<case>>/*keyword*/ <<_>>/*variable,readonly*/ <<=>>>/*operator*/ <<0>>/*number*/
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

  check(
    "self-type",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<object>>/*keyword*/ <<Abc>>/*class*/ { self: Any <<=>>>/*operator*/
       |  <<val>>/*keyword*/ <<xyz>>/*variable,definition,readonly*/ = <<1>>/*number*/
       |}
       |""".stripMargin,
  )

  check(
    "self-type2",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<import>>/*keyword*/ <<scala>>/*namespace*/.<<util>>/*namespace*/.<<chaining>>/*class*/.<<_>>/*variable,readonly*/
       |
       |
       |<<trait>>/*keyword*/ <<User>>/*interface,abstract*/ {
       |  <<def>>/*keyword*/ <<username>>/*method,declaration,abstract*/: <<String>>/*type*/
       |}
       |
       |<<trait>>/*keyword*/ <<Tweeter>>/*interface,abstract*/ {
       |  self: <<User>>/*interface,abstract*/ <<=>>>/*operator*/  <<// reassign this>>/*comment*/
       |  <<def>>/*keyword*/ <<tweet>>/*method,definition*/(<<tweetText>>/*parameter,declaration,readonly*/: <<String>>/*type*/) = <<println>>/*method*/(<<s>>/*keyword*/<<">>/*string*/<<$>>/*keyword*/<<username>>/*method,abstract*/<<: >>/*string*/<<$>>/*keyword*/<<tweetText>>/*parameter,readonly*/<<">>/*string*/)
       |}
       |""".stripMargin,
  )

  check(
    "wildcard-import",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<import>>/*keyword*/ <<scala>>/*namespace*/.<<util>>/*namespace*/.<<chaining>>/*class*/.<<_>>/*variable,readonly*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {}
       |""".stripMargin,
  )

  check(
    "import-selector",
    """|<<package>>/*keyword*/ <<a>>/*namespace*/
       |
       |<<import>>/*keyword*/ <<a>>/*namespace*/.<<Tag>>/*class*/.<<@@>>/*type*/
       |
       |<<object>>/*keyword*/ <<A>>/*class*/ {
       |  <<case>>/*keyword*/ <<class>>/*keyword*/ <<B>>/*class*/(<<c>>/*variable,declaration,readonly*/: <<Int>>/*class,abstract*/)
       |}
       |
       |<<object>>/*keyword*/ <<Tag>>/*class*/ {
       |  <<type>>/*keyword*/ <<@@>>/*type,definition*/ = <<Int>>/*class,abstract*/
       |}""".stripMargin,
  )

  check(
    "interpolated-string",
    s"""|<<package>>/*keyword*/ <<a>>/*namespace*/
        |<<object>>/*keyword*/ <<A>>/*class*/ {
        |<<s>>/*keyword*/<<\"\"\">>/*string*/
        |<<some text>>/*string*/
        |<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
  )

  check(
    "interpolated-string2",
    s"""|<<package>>/*keyword*/ <<a>>/*namespace*/
        |<<object>>/*keyword*/ <<A>>/*class*/ {
        |<<s>>/*keyword*/<<\"\"\">>/*string*/
        |<<some text>>/*string*/
        |<< >>/*string*/<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
  )

  check(
    "escapes",
    s"""|<<object>>/*keyword*/ <<O>>/*class*/ {
        |  <<val>>/*keyword*/ <<stringEscape>>/*variable,definition,readonly*/ = <<"smth >>/*string*/<<\\n>>/*regexp*/<<\\">>/*regexp*/<< rest>>/*string*/<<\\n>>/*regexp*/<<">>/*string*/
        |  <<val>>/*keyword*/ <<multilineString>>/*variable,definition,readonly*/ = <<\"\"\"\\n\"\"\">>/*string*/
        |  <<val>>/*keyword*/ <<charEscape>>/*variable,definition,readonly*/ = <<'>>/*string*/<<\\n>>/*regexp*/<<'>>/*string*/
        |  <<val>>/*keyword*/ <<interpolatorEscape>>/*variable,definition,readonly*/ = <<s>>/*keyword*/<<">>/*string*/<<$$$$>>/*regexp*/<<smth >>/*string*/<<\\n>>/*regexp*/<<\\">>/*regexp*/<< rest>>/*string*/<<">>/*string*/
        |  <<val>>/*keyword*/ <<unicode>>/*variable,definition,readonly*/ = <<s>>/*keyword*/<<\"\"\">>/*string*/<<\\u202c>>/*regexp*/<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
  )

  check(
    "doobie-sql-select",
    s"""|<<import>>/*keyword*/ <<doobie>>/*namespace*/.<<implicits>>/*class*/.<<_>>/*variable,readonly*/
        |
        |<<object>>/*keyword*/ <<SQL>>/*class*/ {
        |  <<val>>/*keyword*/ <<query>>/*variable,definition,readonly*/ = <<sql>>/*keyword*/<<\"\"\">>/*string*/<<SELECT>>/*keyword*/ <<name>>/*variable*/, <<age>>/*variable*/ <<FROM>>/*keyword*/ <<users>>/*variable*/ <<WHERE>>/*keyword*/ <<age>>/*variable*/ <<>>>/*operator*/ <<30>>/*number*/<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
    libraryDependencies = List("org.tpolecat::doobie-core:1.0.0-RC2"),
  )

  check(
    "doobie-fragment-composition",
    s"""|<<import>>/*keyword*/ <<doobie>>/*namespace*/.<<implicits>>/*class*/.<<_>>/*variable,readonly*/
        |
        |<<object>>/*keyword*/ <<SQL>>/*class*/ {
        |  <<val>>/*keyword*/ <<baseQuery>>/*variable,definition,readonly*/ = <<fr>>/*keyword*/<<\"\"\">>/*string*/<<SELECT>>/*keyword*/ <<name>>/*variable*/, <<age>>/*variable*/ <<FROM>>/*keyword*/ <<users>>/*variable*/<<\"\"\">>/*string*/
        |  <<val>>/*keyword*/ <<condition>>/*variable,definition,readonly*/ = <<fr>>/*keyword*/<<\"\"\">>/*string*/<<WHERE>>/*keyword*/ <<age>>/*variable*/ <<>>>/*operator*/ <<30>>/*number*/<<\"\"\">>/*string*/
        |  <<val>>/*keyword*/ <<query>>/*variable,definition,readonly*/ = <<baseQuery>>/*variable,readonly*/ <<++>>/*method*/ <<condition>>/*variable,readonly*/
        |}
        |""".stripMargin,
    libraryDependencies = List("org.tpolecat::doobie-core:1.0.0-RC2"),
  )

  check(
    "doobie-sql-with-variable-literal",
    s"""|<<import>>/*keyword*/ <<doobie>>/*namespace*/.<<implicits>>/*class*/.<<_>>/*variable,readonly*/
        |
        |<<object>>/*keyword*/ <<SQL>>/*class*/ {
        |  <<val>>/*keyword*/ <<username>>/*variable,definition,readonly*/ = <<"John">>/*string*/
        |  <<val>>/*keyword*/ <<query>>/*variable,definition,readonly*/ = <<sql>>/*keyword*/<<\"\"\">>/*string*/<<SELECT>>/*keyword*/ <<*>>/*operator*/ <<FROM>>/*keyword*/ <<users>>/*variable*/ <<WHERE>>/*keyword*/ <<name>>/*variable*/ <<=>>/*operator*/ <<'prefix->>/*string*/<<$$>>/*keyword*/<<username>>/*variable,readonly*/<<-suffix'>>/*string*/<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
    libraryDependencies = List("org.tpolecat::doobie-core:1.0.0-RC2"),
  )

  check(
    "doobie-postgresql-cast-operators",
    s"""|<<import>>/*keyword*/ <<doobie>>/*namespace*/.<<implicits>>/*class*/.<<_>>/*variable,readonly*/
        |
        |<<object>>/*keyword*/ <<SQL>>/*class*/ {
        |  <<val>>/*keyword*/ <<query>>/*variable,definition,readonly*/ = <<fr>>/*keyword*/<<\"\"\">>/*string*/<<SELECT>>/*keyword*/
        |    <<jsonb_path_query>>/*function*/(<<entry>>/*variable*/, <<'dateRange'>>/*string*/)<<::>>/*operator*/<<TEXT>>/*keyword*/<<::>>/*operator*/<<DATE>>/*keyword*/ <<as>>/*keyword*/ <<from_date>>/*variable*/
        |    <<FROM>>/*keyword*/ <<subq0>>/*variable*/<<\"\"\">>/*string*/
        |}
        |""".stripMargin,
    libraryDependencies = List("org.tpolecat::doobie-core:1.0.0-RC2"),
  )

  check(
    "doobie-sql-with-dollar-escape",
    s"""|<<import>>/*keyword*/ <<doobie>>/*namespace*/.<<implicits>>/*class*/.<<_>>/*variable,readonly*/
        |
        |<<object>>/*keyword*/ <<SQL>>/*class*/ {
        |  <<val>>/*keyword*/ <<query>>/*variable,definition,readonly*/ = <<fr>>/*keyword*/<<\">>/*string*/<<SELECT>>/*keyword*/ <<'$$$$.path'>>/*string*/<<::>>/*operator*/<<TEXT>>/*keyword*/ <<FROM>>/*keyword*/ <<t>>/*variable*/<<\">>/*string*/
        |}
        |""".stripMargin,
    libraryDependencies = List("org.tpolecat::doobie-core:1.0.0-RC2"),
  )

  test("new-changes") {
    val expected =
      """|<<package>>/*keyword*/ <<a>>/*namespace*/
         |<<object>>/*keyword*/ <<Main>>/*class*/ {
         |  <<def>>/*keyword*/ <<a>>/*method,definition*/: <<String>>/*type*/ = {
         |    <<val>>/*keyword*/ <<b>>/*variable,definition,readonly*/ = <<3>>/*number*/
         |    <<b>>/*variable,readonly*/ <<+>>/*method,deprecated,abstract*/ <<4>>/*number*/
         |""".stripMargin
    val fileContent = expected.replaceAll(raw"<<|>>|/\*.*?\*/", "")
    for {
      _ <- initialize(
        s"""/metals.json
           |{"a":{}}
           |/a/src/main/scala/a/Main.scala
           |
           |/a/src/main/scala/a/OtherFile.scala
           |package a
           |object A
           |""".stripMargin,
        expectError = true,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "enable-semantic-highlighting": true
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didChange("a/src/main/scala/a/Main.scala")(_ => fileContent)
      _ <- server.didSave("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("a/src/main/scala/a/OtherFile.scala")
      // triggers outline compile on `Main.scala`
      _ <- server.assertSemanticHighlight(
        "a/src/main/scala/a/OtherFile.scala",
        """|<<package>>/*keyword*/ <<a>>/*namespace*/
           |<<object>>/*keyword*/ <<A>>/*class*/
           |""".stripMargin,
        """|package a
           |object A
           |""".stripMargin,
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      // tests if we do full compile after outline compile
      _ <- server.assertSemanticHighlight(
        "a/src/main/scala/a/Main.scala",
        expected,
        fileContent,
      )
    } yield ()
  }

  def check(
      name: TestOptions,
      expected: String,
      fileName: String = "Main.scala",
      libraryDependencies: List[String] = Nil,
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
             |{
             |  "a": {
             |    "libraryDependencies": ${libraryDependencies.map("\"" + _ + "\"").mkString("[", ",", "]")}
             |  }
             |}
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
