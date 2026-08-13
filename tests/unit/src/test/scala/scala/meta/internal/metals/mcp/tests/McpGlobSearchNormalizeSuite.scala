package scala.meta.internal.metals.mcp.tests

import scala.util.Random

import scala.meta.internal.metals.mcp.McpPrinter._
import scala.meta.internal.metals.mcp.McpQueryEngine
import scala.meta.internal.metals.mcp.SymbolSearchResult
import scala.meta.internal.metals.mcp.SymbolType

import tests.BaseSuite

/**
 * Tests for the pure part of glob search: deduplicating, ranking, capping and
 * display sorting of results. The underlying indexes are iterated in an
 * unspecified order, so all of it has to be independent of the input order.
 *
 * Ranking order: exact name match, then shortest name, then fewest path
 * segments, then path alphabetically.
 */
class McpGlobSearchNormalizeSuite extends BaseSuite {

  /** The symbol is only a tie-breaker here, so it needs no realistic shape. */
  private def result(
      path: String,
      symbolType: SymbolType = SymbolType.Class,
      symbol: String = "",
  ): SymbolSearchResult =
    SymbolSearchResult(
      path,
      symbolType,
      if (symbol.isEmpty) path.replace('.', '/') + "#" else symbol,
    )

  private val mixed: Seq[SymbolSearchResult] = Seq(
    result("com.example.ThingBuilder"),
    result("com.example.Thing", SymbolType.Object),
    result("com.example.Thing"),
    result("com.example.deeper.Thing", SymbolType.Trait),
    result("com.example.ThingFactoryProvider"),
    result("org.other.Thing", SymbolType.Package, symbol = "org/other/Thing/"),
  )

  /** Seeded, so that a failure names a reproducible input order. */
  private val orderings: Seq[(String, Seq[SymbolSearchResult])] =
    Seq("original" -> mixed, "reversed" -> mixed.reverse) ++
      Seq(1L, 2L, 3L).map(seed =>
        s"shuffled with seed $seed" -> new Random(seed).shuffle(mixed)
      )

  private def checkEveryOrdering(
      limit: Int,
      expectedShow: String,
      expectedTruncated: Boolean,
  ): Unit =
    orderings.foreach { case (ordering, input) =>
      val (shown, truncated) = McpQueryEngine.normalize("Thing", input, limit)
      assertNoDiff(shown.show, expectedShow, s"input order: $ordering")
      assertEquals(truncated, expectedTruncated, s"input order: $ordering")
    }

  test("order-independent") {
    checkEveryOrdering(
      limit = 10,
      """|class com.example.Thing
         |class com.example.ThingBuilder
         |class com.example.ThingFactoryProvider
         |object com.example.Thing
         |package org.other.Thing
         |trait com.example.deeper.Thing
         |""".stripMargin,
      expectedTruncated = false,
    )
  }

  test("order-independent-when-truncated") {
    // exact name matches win, `com.example` before `org.other` alphabetically,
    // `com.example.deeper` loses on path length
    checkEveryOrdering(
      limit = 3,
      """|class com.example.Thing
         |object com.example.Thing
         |package org.other.Thing
         |""".stripMargin,
      expectedTruncated = true,
    )
  }

  test("cap-below-one-still-shows-a-result") {
    checkEveryOrdering(
      limit = 0,
      """|class com.example.Thing
         |""".stripMargin,
      expectedTruncated = true,
    )
  }

  test("relevance-beats-alphabet") {
    val results = Seq(
      result("com.aaa.ThingBuilder"),
      result("com.zzz.Thing"),
    )
    val (shown, truncated) = McpQueryEngine.normalize("thing", results, 1)

    assert(truncated)
    // an exact, case-insensitive name match survives a cap of 1, even though it
    // sorts last alphabetically and came last in the input
    assertNoDiff(
      shown.show,
      """|class com.zzz.Thing
         |""".stripMargin,
    )
  }

  test("duplicate-rows-collapse") {
    val overloads = Seq(
      result(
        "com.example.Thing.overloaded",
        SymbolType.Method,
        symbol = "com/example/Thing#overloaded(+1).",
      ),
      result(
        "com.example.Thing.overloaded",
        SymbolType.Method,
        symbol = "com/example/Thing#overloaded().",
      ),
    )
    val (shown, truncated) =
      McpQueryEngine.normalize("overloaded", overloads, 2)

    assertEquals(shown.size, 1)
    assertEquals(truncated, false)
    // the surviving symbol does not depend on the input order
    assertNoDiff(shown.head.symbol, "com/example/Thing#overloaded().")
    assertNoDiff(
      McpQueryEngine.normalize("overloaded", overloads.reverse, 2)._1.show,
      shown.show,
    )
  }
}
