package tests.worksheets

import tests.BaseLspSuite
import scala.meta.internal.metals.MetalsEnrichments._

object WorksheetNoDecorationsLspSuite extends BaseLspSuite("worksheet-no-decorations") {

  testAsync("one-liners"){
    for {
      _ <- server.initialize(
          s"""
             |/metals.json
             |{
             |  "a": { }
             |}
             |/a/src/main/scala/foo/Main.worksheet.sc
             |val x = 1
             |val foo = "bar"
             |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Main.worksheet.sc").readText,
        """
          |val x = 1  /*>  x: Int = 1  */
          |val foo = "bar"  /*>  foo: String = "bar"  */
        """.stripMargin
      )
    } yield ()
  }

  testAsync("multiline-value"){
    for {
      _ <- server.initialize(
          s"""
             |/metals.json
             |{
             |  "a": { }
             |}
             |/a/src/main/scala/foo/Main.worksheet.sc
             |val foo = {println("foo is printed"); "metals"}
             |val bar = 42
             |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Main.worksheet.sc").readText,
        """
          |val foo = {println("foo is printed"); "metals"}  /*>  foo: String = "metals"
          |                                                  *   // foo is printed  */
          |val bar = 42  /*>  bar: Int = 42  */
        """.stripMargin
      )
    } yield ()
  }

  testAsync("new-edits"){
    for {
      _ <- server.initialize(
          s"""
             |/metals.json
             |{
             |  "a": { }
             |}
             |/a/src/main/scala/foo/Main.worksheet.sc
             |val foo = "meta ls"  /*>  foo: String = "metals"
             |                                                  *   // foo is printed  */
             |val bar = 43  /*>  bar: Int = 42  */
             |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiff(
        workspace.resolve("a/src/main/scala/foo/Main.worksheet.sc").readText,
        """
          |val foo = "meta ls"  /*>  foo: String = "meta ls"  */
          |val bar = 43  /*>  bar: Int = 43  */
        """.stripMargin
      )
    } yield ()
  }

}