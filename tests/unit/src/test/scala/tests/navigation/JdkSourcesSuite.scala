package tests.navigation

import scala.meta.internal.metals.JdkSources
import tests.BaseSuite

class JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    JdkSources.getOrThrow()
  }
}
