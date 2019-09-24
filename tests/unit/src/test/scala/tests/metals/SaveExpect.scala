package tests
package metals

object SaveExpect {
  def main(args: Array[String]): Unit = {
    List[BaseExpectSuite](
      definition.DefinitionSuite,
      mtags.SemanticdbSuite,
      mtags.MtagsSuite,
      mtags.ToplevelSuite,
      documentsymbol.DocumentSymbolSuite,
      foldingrange.FoldingRangeSuite,
      workspacesymbol.WorkspaceSymbolExpectSuite
    ).foreach { suite =>
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      suite.saveExpect()
    }
  }
}
