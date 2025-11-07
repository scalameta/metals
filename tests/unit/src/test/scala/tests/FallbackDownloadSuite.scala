package tests

import scala.meta.internal.metals.Embedded

class FallbackDownloadSuite extends BaseSuite {

  test("download-mtags") {
    val dependency =
      Embedded.dependencyOf("org.scalameta", "mtags_2.12.18", "1.4.0")
    val results = Embedded.fallbackDownload(dependency)
    assert(
      results.exists(_.toString.contains("mtags_2.12.18-1.4.0.jar")),
      "Fallback should download mtags using local coursier",
    )
    assertEquals(results.size, 37)
  }

}
