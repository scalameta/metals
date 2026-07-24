package tests.bazel

import scala.meta.internal.metals.mbt.importer.BazelScalaVersions

import tests.BaseSuite

class BazelScalaVersionSuite extends BaseSuite {

  test("picks-highest") {
    assertEquals(
      BazelScalaVersions.maxVersion(List("2.12.21", "3.3.7", "2.13.18")),
      Some("3.3.7"),
    )
  }

  test("non-numeric-value-is-skipped-not-thrown") {
    assertEquals(
      BazelScalaVersions.maxVersion(List("scala3", "2.13.18")),
      Some("2.13.18"),
    )
  }

  test("all-unparseable-yields-none") {
    assertEquals(
      BazelScalaVersions.maxVersion(List("scala3", "latest")),
      None,
    )
  }

  test("empty-yields-none") {
    assertEquals(BazelScalaVersions.maxVersion(Nil), None)
  }

  test("preserves-original-string-of-the-max") {
    assertEquals(
      BazelScalaVersions.maxVersion(List("2.13", "2.12.21")),
      Some("2.13"),
    )
  }
}
