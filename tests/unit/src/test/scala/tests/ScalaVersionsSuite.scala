package tests

import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

class ScalaVersionsSuite extends BaseSuite {

  test("idempotent-minor-release") {
    assert(
      ScalaVersions.dropVendorSuffix("2.12.4") ==
        "2.12.4"
    )
  }

  test("retain-pre-release-version") {
    assert(
      ScalaVersions.dropVendorSuffix("2.13.0-RC1") ==
        "2.13.0-RC1"
    )
    assert(
      ScalaVersions.dropVendorSuffix("2.13.0-M5") ==
        "2.13.0-M5"
    )
  }

  test("drop-typelevel-vendor-suffix") {
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

  test("future-213") {
    assert(
      ScalaVersions.isFutureVersion("2.13.31")
    )
  }

  test("not-future-213") {
    assert(
      !ScalaVersions.isFutureVersion("2.13.1")
    )
  }

  test("future-212") {
    assert(
      ScalaVersions.isFutureVersion("2.12.31")
    )
  }

  test("not-future-212") {
    assert(
      !ScalaVersions.isFutureVersion("2.12.10")
    )
  }

  test("not-future-211") {
    assert(
      !ScalaVersions.isFutureVersion("2.11.10")
    )
  }

  test("future-214") {
    assert(
      ScalaVersions.isFutureVersion("2.15.10")
    )
  }

  test("future-315") {
    assert(
      ScalaVersions.isFutureVersion("3.15.10")
    )
  }

  test("2.12.11-comapatible-with-2.12.5") {
    assert(
      SemVer.isCompatibleVersion("2.12.5", "2.12.11")
    )
  }

  test("2.12.5-not-compatible-with-2.12.11") {
    assert(
      !SemVer.isCompatibleVersion("2.12.11", "2.12.5")
    )
  }

  test("2.12.7-compatible-with-2.12.5") {
    assert(
      SemVer.isCompatibleVersion("2.12.5", "2.12.7")
    )
  }

  test("2.12.5-not-compatible-with-2.12.7") {
    assert(
      !SemVer.isCompatibleVersion("2.12.7", "2.12.5")
    )
  }

  test("2.12.11-compatible-with-2.11.12") {
    assert(
      SemVer.isCompatibleVersion("2.11.12", "2.12.11")
    )
  }

  test("2.11.12-not-compatible-with-2.12.11") {
    assert(
      !SemVer.isCompatibleVersion("2.12.11", "2.11.12")
    )
  }

  test("0.9.10-not-compatible-with-0.10.0-M4") {
    assert(
      !SemVer.isCompatibleVersion("0.10.0-M4", "0.9.10")
    )
  }

  test("0.9.10-not-compatible-with-0.10.0-RC2") {
    assert(
      !SemVer.isCompatibleVersion("0.10.0-RC2", "0.9.10")
    )
  }

  test("recommended-3") {
    assert(
      ScalaVersions.recommendedVersion("3.0.0-M1") ==
        V.scala3
    )
  }

  test("compare-3.0.0-M1<=3.0.0-M2") {
    assert(
      SemVer.isCompatibleVersion("3.0.0-M1", "3.0.0-M2")
    )
  }

  test("compare-3.0.0-M2>=3.0.0-M1") {
    assert(
      !SemVer.isCompatibleVersion("3.0.0-M2", "3.0.0-M1")
    )
  }

  test("compare-3.0.0-RC1<=3.0.0-RC2") {
    assert(
      SemVer.isCompatibleVersion("3.0.0-RC1", "3.0.0-RC2")
    )
  }

  test("compare-3.0.0-RC2>=3.0.0-RC1") {
    assert(
      !SemVer.isCompatibleVersion("3.0.0-RC2", "3.0.0-RC1")
    )
  }

  test("compare-3.0.0-RC1<=3.0.0") {
    assert(
      SemVer.isCompatibleVersion("3.0.0-RC1", "3.0.0")
    )
  }

  test("compare-3.0.0>=3.0.0-RC1") {
    assert(
      !SemVer.isCompatibleVersion("3.0.0", "3.0.0-RC1")
    )
  }

  test("compare-3.0.0-M1<=3.0.0") {
    assert(
      SemVer.isCompatibleVersion("3.0.0-M1", "3.0.0")
    )
  }

  test("compare-3.0.0>=3.0.0-M1") {
    assert(
      !SemVer.isCompatibleVersion("3.0.0", "3.0.0-M1")
    )
  }

  test("compare-3.0.0-RC1<3.0.0") {
    assert(
      SemVer.isLaterVersion("3.0.0-RC1", "3.0.0")
    )
  }

  test("compare-3.0.0>3.0.0-RC1") {
    assert(
      !SemVer.isLaterVersion("3.0.0", "3.0.0-RC1")
    )
  }

  test("compare-3.0.0-M1<3.0.0") {
    assert(
      SemVer.isLaterVersion("3.0.0-M1", "3.0.0")
    )
  }

  test("compare-3.0.0>3.0.0-M1") {
    assert(
      !SemVer.isLaterVersion("3.0.0", "3.0.0-M1")
    )
  }

  test("compare-3.0.0-M1<3.0.0-RC1") {
    assert(
      SemVer.isLaterVersion("3.0.0-M1", "3.0.0-RC1")
    )
  }

  test("compare-3.0.0-RC1>3.0.0-M1") {
    assert(
      !SemVer.isLaterVersion("3.0.0-RC1", "3.0.0-M1")
    )
  }

  test("compare-RC1<=RC1-SNAPSHOT") {
    assert(
      SemVer.isCompatibleVersion(
        "3.0.0-RC1-bin-20201125-1c3538a-NIGHTLY",
        "3.0.0-RC2-bin-20201125-1c3538a-NIGHTLY",
      )
    )
  }

  test("compare-RC2>=RC1-SNAPSHOT") {
    assert(
      !SemVer.isCompatibleVersion(
        "3.0.0-RC2-bin-20201125-1c3538a-NIGHTLY",
        "3.0.0-RC1-bin-20201125-1c3538a-NIGHTLY",
      )
    )
  }

  test("compare-RC1<RC2-SNAPSHOT") {
    assert(
      SemVer.isLaterVersion(
        "3.0.0-RC1-bin-20201125-1c3538a-NIGHTLY",
        "3.0.0-RC2-bin-20201125-1c3538a-NIGHTLY",
      )
    )
  }

  test("compare-RC2>RC1-SNAPSHOT") {
    assert(
      !SemVer.isLaterVersion(
        "3.0.0-RC2-bin-20201125-1c3538a-NIGHTLY",
        "3.0.0-RC1-bin-20201125-1c3538a-NIGHTLY",
      )
    )
  }

  test("compare-NIGTLY") {
    assert(
      SemVer.isLaterVersion(
        "3.0.0-RC1-bin-20201125-1c3538a-NIGHTLY",
        "3.0.0-RC1-bin-20201126-1c3538a-NIGHTLY",
      )
    )
  }

  test("not-future-3-M1") {
    assert(
      !ScalaVersions.isFutureVersion("3.0.0-M1")
    )
  }

  test("not-future-3-M2") {
    assert(
      !ScalaVersions.isFutureVersion("3.0.0-M2")
    )
  }

  test("not-future-RC") {
    assert(
      !ScalaVersions.isFutureVersion("3.1.1-RC2")
    )
  }

  test("from-jar-name") {
    val expected =
      List(
        ("smth-library_2.13-21.2.0-sources.jar", "2.13"),
        (
          "scala3-compiler_3-3.0.1-RC2-bin-20210310-4af1386-NIGHTLY-sources.jar",
          "3",
        ),
        ("scala3-library_3-3.1.0-RC1.jar", "3"),
        ("scala-library-2.13.1.jar", "2.13"),
        ("cool4.4_2.13-3.0.jar", "2.13"),
        ("scala3-library_3-3.0.0-sources.jar", "3"),
        ("munit_3-0.7.29-sources.jar", "3"),
      )
    expected.foreach { case (jar, version) =>
      val out = ScalaVersions.scalaBinaryVersionFromJarName(jar)
      assertEquals(out, version, jar)
    }
  }

  def scalaVersions(supportedScalaVersions: String*): ScalaVersions = {
    val deprecatedScalaVersions = Seq.empty[String]
    val supportedScalaBinaryVersions =
      Seq("2.13", "2.12", "2.11", "3")
    val scala212: String = "2.12.17"
    val scala213: String = "2.13.10"
    val scala3: String = "3.2.1"
    new ScalaVersions(
      deprecatedScalaVersions,
      supportedScalaVersions,
      supportedScalaBinaryVersions,
      scala212,
      scala213,
      scala3,
    )
  }

  test("newer-RC") {
    val sv = scalaVersions("2.13.10", "2.12.17", "3.2.2-RC2", "3.2.1")
    assertEquals(sv.recommendedVersion("3.2.2-RC1"), "3.2.2-RC2")
    assert(!sv.isFutureVersion("3.2.2-RC1"))
  }

}
