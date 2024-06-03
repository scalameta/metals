package tests.feature

import tests.BaseExpectSuite

object SlowSaveExpect {
  def main(args: Array[String]): Unit = {
    List[BaseExpectSuite](
      new SemanticTokensScala2ExpectSuite(),
      new InlayHintsScala2ExpectSuite(),
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
