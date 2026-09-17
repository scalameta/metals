package tests

import scala.meta.internal.semver.SemVer

import munit.FunSuite

class SemVerSuite extends FunSuite {

  val expected: List[(String, SemVer.Version)] = List(
    ("3.0.0", SemVer.Version(3, 0, 0)),
    (
      "3.0.0-M1",
      SemVer.Version(3, 0, 0, milestone = Some(1), isStable = false),
    ),
    (
      "3.0.0-RC1",
      SemVer.Version(3, 0, 0, releaseCandidate = Some(1), isStable = false),
    ),
    (
      "3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY",
      SemVer.Version(
        3,
        2,
        0,
        releaseCandidate = Some(1),
        nightlyDate = Some(20220307),
        isStable = false,
      ),
    ),
    // Qualifier follows a two segment numeric core.
    ("3.7-M1", SemVer.Version(3, 7, 0, milestone = Some(1), isStable = false)),
    // Commit distance and hash must not be read as a patch.
    (
      "3.7-4972921",
      SemVer
        .Version(3, 7, 0, developmentBuild = Some(4972921L), isStable = false),
    ),
    (
      "3.2-148-d9af944",
      SemVer.Version(3, 2, 0, developmentBuild = Some(148L), isStable = false),
    ),
    (
      "1.4.13-18-3cc4983b-20220225-1641",
      SemVer.Version(
        1,
        4,
        13,
        developmentBuild = Some(18L),
        isStable = false,
      ),
    ),
    (
      "1.0.0-MF",
      SemVer.Version(1, 0, 0, milestone = Some(0), isStable = false),
    ),
    ("4.12.4.1", SemVer.Version(4, 12, 4)),
    ("4.1.138.Final", SemVer.Version(4, 1, 138)),
    ("1.0-GA", SemVer.Version(1, 0, 0)),
  )

  test("fromString") {
    val incorrect = expected
      .map { case (version, expected) =>
        (version, expected, SemVer.Version.fromString(version))
      }
      .filter({ case (_, expected, parsed) => parsed != expected })

    assert(
      incorrect.isEmpty,
      incorrect.mkString(
        "Failed to parse versions(version, expected, got):\n",
        "\n",
        "",
      ),
    )
  }

  test("pre-release-is-not-the-full-release") {
    assert(
      !SemVer.isCompatibleVersion("3.7.0", "3.7-M1"),
      "A milestone should be earlier than the corresponding full release.",
    )
  }

  test("isStable") {
    val stable = List(
      "3.7.0", "4.12.4.1", "1.0", "33.7.1-jre", "33.7.1-android",
      "4.1.138.Final", "5.3.39.RELEASE", "1.0-GA", "1.7.36-jdk8",
    )
    val unstable = List(
      "3.7.0-M1", "3.7.0-RC1", "3.2-148-d9af944", "3.7-4972921",
      "1.6.10-SNAPSHOT", "1.0.0-alpha01", "1.0.0-MF", "2.0.0-beta3",
      "3.7.0-RC1-bin-20220307-6dc591a-NIGHTLY",
      "1.4.13-18-3cc4983b-20220225-1641",
    )
    stable.foreach(version =>
      assert(SemVer.Version.fromString(version).isStable, version)
    )
    unstable.foreach(version =>
      assert(!SemVer.Version.fromString(version).isStable, version)
    )
  }

  test("stableFirst") {
    val versions =
      List(
        "3.7.0-RC1", "3.7-4972921", "3.7-6000000", "3.6.4", "3.7.0", "3.7.0-M1",
      )
    assertEquals(
      versions.sortBy(SemVer.Version.fromString)(SemVer.Version.stableFirst),
      List(
        "3.7.0", "3.6.4", "3.7-6000000", "3.7-4972921", "3.7.0-RC1", "3.7.0-M1",
      ),
    )
  }

  test("bloop-version") {
    assert(
      SemVer.isCompatibleVersion("1.4.13", "1.4.13-18-3cc4983b-20220225-1641"),
      "Bloop nightlies should be later than the corresponding full release.",
    )
  }
  test("scala-nightlies") {
    assert(
      SemVer
        .isCompatibleVersion("3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY", "3.2.0"),
      "Scala nightlies should be earlier than the corresponding full release.",
    )
    assert(
      SemVer
        .isCompatibleVersion(
          "3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY",
          "3.2.0-RC1",
        ),
      "Scala nightlies should be earlier than the corresponding full release.",
    )
    assert(
      !SemVer.isCompatibleVersion(
        "3.3.2-RC1-bin-20230706-3ae2dbf-NIGHTLY",
        "3.3.1-RC1",
      ),
      "Scala nightlies should be later than the previous release candidade.",
    )
  }
}
