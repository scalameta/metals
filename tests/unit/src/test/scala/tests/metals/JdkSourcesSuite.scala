package tests.metals

import scala.meta.internal.metals.JdkSources

object JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    if (isWindows) {
      // src.zip is not available on Appveyor.
      ()
    } else {
      JdkSources.getOrThrow()
    }
  }
}
