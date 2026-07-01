package tests.bazel

import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport

import tests.BaseSuite

/**
 * [[BazelMbtBuildSupport.maxVersion]] — the highest parseable Scala version,
 * skipping unparseable ones. `scala_version` is a free-form Bazel `STRING`
 * attribute, so a single non-numeric value anywhere in the transitive closure
 * must degrade to "ignored" rather than abort the whole import.
 */
class BazelScalaVersionSuite extends BaseSuite {

  test("picks-highest") {
    assertEquals(
      BazelMbtBuildSupport.maxVersion(List("2.12.21", "3.3.7", "2.13.18")),
      Some("3.3.7"),
    )
  }

  test("non-numeric-value-is-skipped-not-thrown") {
    // `SemVer.Version.fromString("scala3")` throws; maxVersion must not.
    assertEquals(
      BazelMbtBuildSupport.maxVersion(List("scala3", "2.13.18")),
      Some("2.13.18"),
    )
  }

  test("all-unparseable-yields-none") {
    assertEquals(
      BazelMbtBuildSupport.maxVersion(List("scala3", "latest")),
      None,
    )
  }

  test("empty-yields-none") {
    assertEquals(BazelMbtBuildSupport.maxVersion(Nil), None)
  }

  test("preserves-original-string-of-the-max") {
    // Two-part versions are kept verbatim (coursier resolution decides), and
    // the returned value is the original string, not a normalized form.
    assertEquals(
      BazelMbtBuildSupport.maxVersion(List("2.13", "2.12.21")),
      Some("2.13"),
    )
  }
}
