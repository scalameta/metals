package tests

import scala.meta.internal.metals.JdkSources

class JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    JdkSources.getOrThrow()
  }
}
