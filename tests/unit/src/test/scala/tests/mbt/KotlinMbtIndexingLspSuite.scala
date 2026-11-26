package tests.mbt

import scala.meta.internal.mtags.Symbol

import org.eclipse.lsp4j as l

/** Indexing of Kotlin sources in a build from `.metals/mbt.json`. */
class KotlinMbtIndexingLspSuite
    extends tests.BaseLspSuite(
      "kotlin-mbt-indexing",
      tests.MbtTestInitializer,
    ) {

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    client.showMessageRequestHandler = { params =>
      if (params.getMessage.contains("needs to be re-imported"))
        Some(new l.MessageActionItem("Not now"))
      else None
    }
  }

  private def facade(symbol: String) =
    server.server.definitionIndex
      .definition(Symbol(symbol))
      .map(_.definitionSymbol.value)

  test("glob-sources-at-startup") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/.metals/mbt.json
           |{ "namespaces": { "app": { "sources": ["**"] } } }
           |/app/src/main/kotlin/a/Account.kt
           |package a
           |
           |fun openAccount(owner: String) = owner
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
    } yield {
      val account = server.toPath("app/src/main/kotlin/a/Account.kt")
      assert(server.server.buildTargets.inverseSources(account).isDefined)
      assertEquals(
        facade("a/AccountKt#openAccount()."),
        Some("a/AccountKt#openAccount()."),
      )
    }
  }
}
