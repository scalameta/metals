package tests

import scala.meta.internal.metals.Fuzzy
import scala.meta.internal.metals.WorkspaceSymbolQuery

class WorkspaceSymbolQuerySuite extends munit.FunSuite {
  test("exactDescriptorPart") {
    val fuzzy = WorkspaceSymbolQuery.fuzzy("InputStream")
    val exact = WorkspaceSymbolQuery.exactDescriptorPart("InputStream")
    val noExactMatch = Fuzzy.bloomFilterSymbolStrings(
      List(
        "scala/collection/immutable/Input#",
        "scala/collection/immutable/Input#getInputStream",
        "scala/collection/immutable/Stream#",
        "scala/collection/immutable/InputStreamProvider#",
        "scala/collection/immutable/ProviderInputStream#",
      )
    )
    assert(fuzzy.matches(noExactMatch))
    assert(!exact.matches(noExactMatch))

    val hasExactMatch = Fuzzy.bloomFilterSymbolStrings(
      List(
        "java/io/InputStream#",
        "java/io/InputStream#read",
      )
    )
    assert(fuzzy.matches(hasExactMatch))
    assert(exact.matches(hasExactMatch))

    List("scala", "collection", "immutable").foreach { pkg =>
      assert(
        !WorkspaceSymbolQuery
          .exactDescriptorPart(clue(pkg))
          .matches(noExactMatch)
      )
    }
  }
}
