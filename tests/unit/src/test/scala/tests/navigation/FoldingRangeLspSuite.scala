package tests.navigation

import scala.concurrent.Future
import munit.Location
import tests.BaseLspSuite

class FoldingRangeLspSuite extends BaseLspSuite("foldingRange") {
  test("parse-error") {
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

  private def assertFolded(filename: String, expected: String)(
      implicit loc: Location
  ): Future[Unit] =
    for {
      folded <- server.foldingRange(filename)
      _ = assertNoDiff(folded, expected)
    } yield ()
}
