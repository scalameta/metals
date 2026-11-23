package tests

import scala.meta.internal.metals.config.UserConfiguration
import scala.meta.internal.metals.config.WorkspaceSymbolProviderConfig

/** Startup indexing of build target sources, used by the BSP symbol provider. */
class KotlinBspIndexingLspSuite extends BaseLspSuite("kotlin-bsp-indexing") {
  override def userConfig: UserConfiguration = super.userConfig.copy(
    workspaceSymbolProvider = WorkspaceSymbolProviderConfig.BSP
  )

  test("kotlin-source-indexed-at-startup") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Account.kt
           |package a
           |
           |class Account(val owner: String) {
           |  fun deposit(amount: Long) {}
           |}
           |""".stripMargin
      )
      _ = assertNoDiff(
        server
          .workspaceSymbol("deposit")
          .linesIterator
          .filterNot(_.startsWith("Add ';'")) // shown on some runs only
          .mkString("\n"),
        "a.Account#deposit",
      )
    } yield ()
  }
}
