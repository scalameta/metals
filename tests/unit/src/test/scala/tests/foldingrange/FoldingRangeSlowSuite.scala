package tests
package foldingrange

import scala.concurrent.Future

object FoldingRangeSlowSuite extends BaseSlowSuite("foldingRange") {
  testAsync("parse-error") {
    for {
      _ <- server.initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object Main {
           |  def foo = {
           |    ???
           |    ???
           |    ???
           |  }
           |
           |  val justAPadding = ???
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- assertFolded(
        "a/src/main/scala/a/Main.scala",
        """object Main >>region>>{
          |  def foo = >>region>>{
          |    ???
          |    ???
          |    ???
          |  }<<region<<
          |
          |  val justAPadding = ???
          |}<<region<<""".stripMargin
      )
      _ <- server.didChange("a/src/main/scala/a/Main.scala") { text =>
        "__" + "\n\n" + text
      }
      _ <- assertFolded(
        "a/src/main/scala/a/Main.scala",
        """__
          |
          |object Main >>region>>{
          |  def foo = >>region>>{
          |    ???
          |    ???
          |    ???
          |  }<<region<<
          |
          |  val justAPadding = ???
          |}<<region<<""".stripMargin
      )
    } yield ()
  }

  private def assertFolded(filename: String, expected: String): Future[Unit] =
    for {
      folded <- server.foldingRange(filename)
      _ = assertNoDiff(folded, expected)
    } yield ()
}
