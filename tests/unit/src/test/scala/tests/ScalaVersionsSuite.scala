package tests

import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.{BuildInfo => V}

class ScalaVersionsSuite extends BaseSuite {

  test("Idempotent minor release") {
    assert(
      ScalaVersions.dropVendorSuffix("2.12.4") ==
        "2.12.4"
    )
  }

  test("Retain pre-release version") {
    assert(
      ScalaVersions.dropVendorSuffix("2.13.0-RC1") ==
        "2.13.0-RC1"
    )
    assert(
      ScalaVersions.dropVendorSuffix("2.13.0-M5") ==
        "2.13.0-M5"
    )
  }

  test("Drop Typelevel vendor suffix") {
    assert(
      ScalaVersions.dropVendorSuffix("2.12.4-bin-typelevel-4") ==
        "2.12.4"
    )
  }

  test("recommended-future") {
    assert(
      ScalaVersions.recommendedVersion(V.scala212 + "1") ==
        V.scala212
    )
  }

  test("recommended-212") {
    assert(
      ScalaVersions.recommendedVersion("2.12.4") ==
        V.scala212
    )
  }

  test("recommended-211") {
    assert(
      ScalaVersions.recommendedVersion("2.11.4") ==
        V.scala212
    )
  }

  test("recommended-213") {
    assert(
      ScalaVersions.recommendedVersion("2.13.0") ==
        V.scala213
    )
  }
}
