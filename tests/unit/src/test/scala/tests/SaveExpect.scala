package tests

import scala.collection.mutable
import scala.util.control.NonFatal

object SaveExpect {
  def main(args: Array[String]): Unit = {
    val filter = args.headOption.map(_.toLowerCase).getOrElse("")
    val errorSuites = mutable.ListBuffer.empty[String]
    for {
      loader <- List[() => BaseExpectSuite](
        () => new DefinitionScala2Suite,
        () => new SemanticdbScala2Suite,
        () => new MtagsScala2Suite,
        () => new ToplevelsScala2Suite,
        () => new ToplevelWithInnerScala2Suite,
        () => new DocumentSymbolScala2Suite,
        () => new FoldingRangeScala2Suite,
        () => new SemanticTokensExpectSuite,
        () => new inlayHints.InlayHintsExpectSuite,
      )
      suite = loader()
      // Uncomment to debug if a class is too slow to load.  This happens if a
      // class does heavy work inside the `testCases()` method.
      // _ = pprint.log(suite.suiteName)
      if filter.isEmpty || suite.suiteName.toLowerCase == filter
    } {
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      try {
        suite.saveExpect()
        suite.afterAll()
      } catch {
        case NonFatal(e) =>
          errorSuites += suite.suiteName
          scribe.error(suite.suiteName, e)
      }
    }
    if (errorSuites.nonEmpty) {
      scribe.error("Tests failed: " + errorSuites.mkString(", "))
      System.exit(1)
    }
  }
}
