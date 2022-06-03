package tests.worksheets

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import munit.Location
import tests.BaseLspSuite
import tests.TestHovers

class WorksheetNoDecorationsLspSuite
    extends BaseLspSuite("worksheet-no-decorations")
    with TestHovers {

  test("edits-and-hovers") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |val x = 1
           |val foo = "bar"
           |println("metals")
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Main.worksheet.sc").readText,
        """
          |val x = 1  /*>  : Int = 1  */
          |val foo = "bar"  /*>  : String = bar  */
          |println("metals")  /*>  metals  */
          |""".stripMargin,
      )
      _ <- assertHovers(
        "a/src/main/scala/foo/Main.worksheet.sc",
        """
          |val x = 1  /*>  : Int@@ = 1  */
          |val foo = "bar"  /*>  : @@String = bar  */
          |println("metals")  /*>  @@metals  */
          |""".stripMargin,
        """x: Int = 1""".hover,
        """foo: String = bar""".hover,
        """// metals""".hover,
      )
    } yield ()
  }

  test("new-edits") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { }
           |}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |val x = 2  /*>  1  */
           |val foo = "baz"  /*>  "bar"  */
           |println("meta ls")  /*>  metals  */
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Main.worksheet.sc").readText,
        """
          |val x = 2  /*>  : Int = 2  */
          |val foo = "baz"  /*>  : String = baz  */
          |println("meta ls")  /*>  meta ls  */
          |""".stripMargin,
      )
      _ <- assertHovers(
        "a/src/main/scala/foo/Main.worksheet.sc",
        """
          |val x = 2  /*>  : Int @@= 2  */
          |val foo = "baz"  /*>  : String = baz@@  */
          |println("meta ls")  /*>  meta@@ ls  */
          |""".stripMargin,
        """x: Int = 2""".hover,
        """foo: String = baz""".hover,
        """// meta ls""".hover,
      )
    } yield ()
  }

  private def assertHovers(
      filename: String,
      query: String,
      expected: String*
  )(implicit loc: Location): Future[Unit] = {
    val queriesAndExpected =
      "@@".r
        .findAllMatchIn(query)
        .map { m =>
          val before = query.substring(0, m.start).replace("@@", "")
          val after = query.substring(m.end).replace("@@", "")
          before + "@@" + after
        }
        .toList
        .zip(expected.toList)

    Future
      .traverse(queriesAndExpected) { case (q, e) =>
        server.assertHover(filename, q, e)
      }
      .map(_ => ())
  }

}
