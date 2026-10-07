package scala.meta.internal.metals.testProvider.frameworks

import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

class WeaverCatsEffectTestFinder(
    trees: Trees,
    symbolIndex: GlobalSymbolIndex,
    textDocument: AbsolutePath => Option[TextDocument],
) extends MunitTestFinder(trees, symbolIndex, textDocument) {
  override protected val baseParentClasses: Set[String] =
    WeaverCatsEffectTestFinder.baseParentClasses
  override protected val testFunctionsNames: Set[String] =
    Set("test", "pureTest", "loggedTest")
}

object WeaverCatsEffectTestFinder {
  val baseParentClasses: Set[String] =
    Set("weaver/MutableFSuite#", "weaver/FunSuiteF#", "weaver/IOSuite#",
      "weaver/SimpleIOSuite#", "weaver/MutableIOSuite#")
}
