package tests

import scala.meta.internal.metals.JdkSources

object JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    JdkSources.getOrThrow()
  }
}
