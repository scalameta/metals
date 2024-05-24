package scala.meta.internal.metals.testProvider.frameworks

import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees

class WeaverCatsEffectTestFinder(
    trees: Trees,
    symbolIndex: GlobalSymbolIndex,
    semanticdbs: () => Semanticdbs,
) extends MunitTestFinder(trees, symbolIndex, semanticdbs) {
  override protected val baseParentClasses: Set[String] =
    Set("weaver/MutableFSuite#", "weaver/FunSuiteF#")
  override protected val testFunctionsNames: Set[String] =
    Set("test", "pureTest", "loggedTest")
}
