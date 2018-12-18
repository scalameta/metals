package tests

object DocumentSymbolSlowSuite extends BaseSlowSuite("documentSymbol") {

  testAsync("parse-error") {
    for {
      // start with code that does not parse (notice the first char in Main.scala)
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |} // <- parse error
           |object Outer {
           |  class Inner
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      // check that no document symbols have been found for the unparseable code
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """|} // <- parse error
           |object Outer {
           |  class Inner
           |}""".stripMargin
      )
      // fix the code to make it parse
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        """|
           |object Outer {
           |  class Inner
           |}""".stripMargin
      }
      // check that all document symbols have been found
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """|
           |/*Outer(Module):4*/object Outer {
           |  /*Inner(Class):3*/class Inner
           |}""".stripMargin
      )
      // make the code unparseable again
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        """|} // <- parse error
           |object Outer {
           |  class Inner
           |}""".stripMargin
      }
      // check that the document symbols haven't changed (fallback to the last snapshot),
      // because the code is unparseable again
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """|} // <- parse error
           |/*Outer(Module):4*/object Outer {
           |  /*Inner(Class):3*/class Inner
           |}""".stripMargin
      )
      // check that when closing the buffer, the snapshot is lost, and no symbols
      // are found for unparseable code
      _ <- server.didClose("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """|} // <- parse error
           |object Outer {
           |  class Inner
           |}""".stripMargin
      )
    } yield ()
  }

}
