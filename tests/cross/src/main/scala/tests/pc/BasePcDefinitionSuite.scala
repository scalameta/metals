package tests.pc

import tests.BasePCSuite

abstract class BasePcDefinitionSuite extends BasePCSuite {
  val runCheck: (String, String) => (String, String) =
    obtainedAndExpected(params => pc.definition(params).thenApply(_.locations())
    )

  def check(
      name: String,
      original: String,
      compat: Map[String, String] = Map.empty
  ): Unit = {
    test(name) {
      val uri = "A.scala"
      val (obtained, expected) = runCheck(original, uri)
      assertNoDiff(obtained, getExpected(expected, compat))
    }
  }
}
