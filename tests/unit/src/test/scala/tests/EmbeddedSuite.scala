package tests

import scala.meta.internal.metals.Embedded

import coursierapi.Dependency
import munit.IgnoreSuite

// This test suite only passes if you have the right credentials
@IgnoreSuite
class EmbeddedSuite extends munit.FunSuite {
  test("customRepositories") {
    val result = Embedded.downloadDependency(
      Dependency.of(
        "org.scalameta",
        "metals_2.13",
        "1.5.1-DATABRICKS-8-1-79000f48",
      ),
      customRepositories = List(
        "https://maven.pkg.github.com/REDACTED_ORG/metals"
      ),
    )
    assert(result.nonEmpty)
  }
}
