package tests.mcp

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.SymbolType

import tests.BaseLspSuite

class McpGlobSearchCapLspSuite extends BaseLspSuite("mcp-glob-search-cap") {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(maxMcpSearchResults = 2)

  test("glob search result cap") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/capped/CappedProbes.scala
           |package com.capped
           |
           |class CappedProbe1
           |class CappedProbe2
           |class CappedProbe3
           |class CappedProbe4
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/capped/CappedProbes.scala")
      _ = assertNoDiagnostics()

      truncated <- server.headServer.queryEngine.globSearch(
        "CappedProbe",
        Set(SymbolType.Class),
      )
      _ = assertEquals(truncated.results.size, 2)
      _ = assertEquals(truncated.cappedByResultLimit, true)
      _ = assertEquals(truncated.searchBudgetExhausted, false)
      _ = assertNoDiff(
        truncated.show,
        """|class com.capped.CappedProbe1
           |class com.capped.CappedProbe2
           |
           |[Showing 2 results; additional matches may exist. Narrow the query.]
           |""".stripMargin,
        "query: globSearch(\"CappedProbe\", Set(SymbolType.Class))",
      )

      // a result set below the cap has no notice at all
      complete <- server.headServer.queryEngine.globSearch(
        "CappedProbe3",
        Set(SymbolType.Class),
      )
      _ = assertEquals(complete.cappedByResultLimit, false)
      _ = assertNoDiff(
        complete.show,
        """|class com.capped.CappedProbe3
           |""".stripMargin,
        "query: globSearch(\"CappedProbe3\", Set(SymbolType.Class))",
      )
    } yield ()
  }
}
