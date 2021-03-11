package tests

object SaveExpect {
  def main(args: Array[String]): Unit = {
    List[BaseExpectSuite](
      new DefinitionSuite,
      new SemanticdbScala2Suite,
      new SemanticdbScala3Suite,
      new MtagsSuite,
      new ToplevelSuite,
      new DocumentSymbolScala2Suite,
      new DocumentSymbolScala3Suite,
      new FoldingRangeScala2Suite,
      new FoldingRangeScala3Suite,
      new WorkspaceSymbolExpectSuite
    ).foreach { suite =>
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      suite.saveExpect()
    }
  }
}
