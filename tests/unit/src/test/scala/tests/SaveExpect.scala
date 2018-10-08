package tests

object SaveExpect {
  def main(args: Array[String]): Unit = {
    List[BaseExpectSuite](
      DefinitionSuite,
      SemanticdbSuite,
      MtagsSuite,
      ToplevelSuite
    ).foreach { suite =>
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      suite.saveExpect()
    }
  }
}
