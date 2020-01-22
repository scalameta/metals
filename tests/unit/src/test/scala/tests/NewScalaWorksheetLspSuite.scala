package tests

import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.MetalsEnrichments._

object NewScalaWorksheetLspSuite extends BaseLspSuite("new-scala-worksheet") {
  testAsync("new-worksheet") {
    for {
      _ <- server.initialize(s"""
                                |/metals.json
                                |{
                                |  "a": { }
                                |}
                                |/a/src/main/scala/dummy
                                |
                                |""".stripMargin)
      _ <- server.executeCommand(
        ServerCommands.NewScalaWorksheet.id,
        workspace.resolve("a/src/main/scala/").toURI.toString,
        "Foo"
      )
      _ = assert(workspace.resolve("a/src/main/scala/Foo.worksheet.sc").exists)
    } yield ()
  }
}
