package tests

import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbolParams
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._
import tests.MetalsTestEnrichments._

object WorkspaceSymbolSlowSuite extends BaseSlowSuite("workspace-symbol") {

  testAsync("basic") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/b/A.scala
          |package a
          |package b
          |
          |object PazQux { // Intentionally obscure name to not conflict with dependency names.
          |
          |  class Inner {
          |    def bar = {
          |      val x = 1
          |    }
          |  }
          |}
          |/a/src/main/scala/a/B.scala
          |package a
          |class B
          |""".stripMargin
      )
      _ = assertNoDiff(
        server.workspaceSymbol("PazQux.Inner"),
        "a.b.PazQux.Inner"
      )
      _ = assertNoDiff(
        server.workspaceSymbol("a.b.PazQux"),
        "a.b.PazQux"
      )
      _ = assertNoDiff(
        server.workspaceSymbol("a.PazQux"),
        ""
      )
      _ <- server.didSave("a/src/main/scala/a/B.scala")(
        _.replaceAllLiterally("class B", "  class HaddockBax")
      )
      _ = assertNoDiff(
        server.workspaceSymbol("Had"),
        "a.HaddockBax"
      )
    } yield ()
  }

  testAsync("pre-initialized") {
    var request = Future.successful[List[List[SymbolInformation]]](Nil)
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/b/A.scala
          |package a
          |package b
          |
          |object PazQux {
          |  class Inner
          |}
          |""".stripMargin,
        preInitialized = { () =>
          request = Future
            .sequence(1.to(10).map { _ =>
              server.server
                .workspaceSymbol(new WorkspaceSymbolParams("PazQux.I"))
                .asScala
                .map(_.asScala.toList)
            })
            .map(_.toList)
          Thread.sleep(10) // take a moment to delay
          Future.successful(())
        }
      )
      results <- request
      _ = {
        val obtained = results.distinct
        // Assert that all results are the same, makesure we don't return empty/incomplete results
        // before indexing is complete.
        assert(obtained.length == 1)
        assert(obtained.head.head.fullPath == "a.b.PazQux.Inner")
      }
    } yield ()
  }

}
