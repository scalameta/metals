package tests

import scala.meta.internal.metals.JdkSources

object JdkSourcesSuite extends BaseSuite {
  test("src.zip") {
    if (isAppveyor) {
      // src.zip is not available on Appveyor.
      ()
    } else {
      JdkSources.getOrThrow()
    }
  }
}
