package tests
import scala.collection.JavaConverters._

/**
 *  Suite for testing selection ranges. Note that this takes some liberty
 *  since the spec isn't the clearest on the way this is done and different
 *  LSP clients seems to impliment this differently. Mainly, we mimic what VS
 *  Code does and what other servers like jdtls do and we send in a single
 *  position. Once that positions is recieved we check the Selection range and
 *  all the parents in that range to ensure it contains the positions we'd
 *  expect it to contain. In the case of VS Code, they never make a second
 *  request, and instead they just rely on the tree to continue selecting. So
 *  we mimic that here in the tests.
 */
class SelectionRangeLspSuite extends BaseQuickBuildSuite("selectionRange") {

  test("basic") {
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object Main extends App {
           |  def double(a: Int) = {
           |    a * 2
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      ranges <- server.retrieveRanges(
        "a/src/main/scala/a/Main.scala",
        """|object Main extends App {
           |  def double(a: Int) = {
           |  @@  a * 2
           |  }
           |}
           |""".stripMargin
      )
      _ = server.assertSelectionRanges(
        "a/src/main/scala/a/Main.scala",
        ranges.asScala.toList,
        List(
          """|object Main extends App {
             |  >>region>>def double(a: Int) = {
             |    a * 2
             |  }<<region<<
             |}
             |""".stripMargin,
          """|object Main >>region>>extends App {
             |  def double(a: Int) = {
             |    a * 2
             |  }
             |}<<region<<
             |""".stripMargin,
          """|>>region>>object Main extends App {
             |  def double(a: Int) = {
             |    a * 2
             |  }
             |}<<region<<
             |""".stripMargin
        )
      )

    } yield ()
  }

  test("non-compiling") {
    for {
      _ <- initialize(
        """|
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/a/Main.scala
           |object Main extends App {
           |  def double(a: Int) = {
           |    * 2
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      ranges <- server.retrieveRanges(
        "a/src/main/scala/a/Main.scala",
        """|object Main extends App {
           |  def double(a:@@ Int) = {
           |    * 2
           |  }
           |}
           |""".stripMargin
      )
      _ = server.assertSelectionRanges(
        "a/src/main/scala/a/Main.scala",
        ranges.asScala.toList,
        List(
          """|object Main extends App {
             |  def double(>>region>>a: Int<<region<<) = {
             |    * 2
             |  }
             |}
             |""".stripMargin,
          """|object Main extends App {
             |  >>region>>def double(a: Int) = {
             |    * 2
             |  }<<region<<
             |}
             |""".stripMargin,
          """|object Main >>region>>extends App {
             |  def double(a: Int) = {
             |    * 2
             |  }
             |}<<region<<
             |""".stripMargin,
          """|>>region>>object Main extends App {
             |  def double(a: Int) = {
             |    * 2
             |  }
             |}<<region<<
             |""".stripMargin
        )
      )
    } yield ()
  }
}
