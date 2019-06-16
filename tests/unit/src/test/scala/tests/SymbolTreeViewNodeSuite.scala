package tests

import scala.meta.internal.tvp.SymbolTreeViewNode

object SymbolTreeViewNodeSuite extends BaseSuite {
  def check(uri: String, expected: (String, String)): Unit = {
    test(uri) {
      val node =
        SymbolTreeViewNode.fromUri[String]("scheme", uri, identity, identity)
      val (expectedBuildTarget, expectedSymbol) = expected
      assertNoDiff(node.value, expectedBuildTarget, "build target identifier")
      assertNoDiff(node.symbol, expectedSymbol, "symbol")
    }
  }

  def checkRoundtrip(buildTarget: String, symbol: String): Unit = {
    val expected =
      SymbolTreeViewNode[String]("scheme", buildTarget, identity, symbol).toUri
    test(expected) {
      val obtained = SymbolTreeViewNode
        .fromUri[String]("scheme", expected, identity, identity)
        .toUri
      assertNoDiff(obtained, expected)
    }
  }

  check("scheme:foo-bar!/com/apple/Foo#", "foo-bar" -> "com/apple/Foo#")
  checkRoundtrip("foo-bar", "com/apple/Foo#")
}
