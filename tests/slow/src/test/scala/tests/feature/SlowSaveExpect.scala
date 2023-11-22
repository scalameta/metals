package tests.feature

import tests.BaseExpectSuite

object SlowSaveExpect {
  def main(args: Array[String]): Unit = {
    List[BaseExpectSuite](
      new SemanticTokensScala3ExpectSuite(),
      new InlayHintsScala3ExpectSuite(),
    ).foreach { suite =>
      val header = suite.suiteName.length + 2
      println("=" * header)
      println("= " + suite.suiteName)
      println("=" * header)
      suite.saveExpect()
      suite.afterAll()
    }
  }

}
