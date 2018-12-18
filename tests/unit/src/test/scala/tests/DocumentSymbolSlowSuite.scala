package tests

object DocumentSymbolSlowSuite extends BaseSlowSuite("documentSymbol") {

  testAsync("documentSymbol") {
    for {
      // start with code that does not parse (notice the first char in Main.scala)
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |}package a
           |import java.util.concurrent.Future // unused
           |import scala.util.Failure // unused
           |object Main extends App {
           |  val message = 42
           |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
           |  println(message)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      // check that no document symbols have been found for the unparseable code
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """
          |}package a
          |import java.util.concurrent.Future // unused
          |import scala.util.Failure // unused
          |object Main extends App {
          |  val message = 42
          |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
          |  println(message)
          |}""".stripMargin
      )
      // fix the code to make it parse
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        text.replaceFirst("}", "")
      }
      // check that all document symbols have been found
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """
          |/*a*/package a
          |import java.util.concurrent.Future // unused
          |import scala.util.Failure // unused
          |/*Main*/object Main extends App {
          |  /*message*/val message = 42
          |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
          |  println(message)
          |}""".stripMargin
      )
      // make the code unparseable again
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        "woops " + text
      }
      // check that the document symbols haven't changed (fallback to the previous result,
      // because the code is unparseable
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """
          |/*a*/woops }package a
          |import java.util.concurrent.Future // unused
          |import scala.util.Failure // unused
          |/*Main*/object Main extends App {
          |  /*message*/val message = 42
          |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
          |  println(message)
          |}""".stripMargin
      )
      // check that when closing the buffer, the snapshot is lost, and no symbols
      // are found for unparseable code
      _ <- server.didClose("a/src/main/scala/a/Main.scala")
      _ = assertNoDiff(
        server.documentSymbols("a/src/main/scala/a/Main.scala"),
        """
          |}package a
          |import java.util.concurrent.Future // unused
          |import scala.util.Failure // unused
          |object Main extends App {
          |  val message = 42
          |  new java.io.PrintStream(new java.io.ByteArrayOutputStream())
          |  println(message)
          |}""".stripMargin
      )
    } yield ()
  }

}
