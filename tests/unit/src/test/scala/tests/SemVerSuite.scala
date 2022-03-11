package tests

import scala.meta.internal.semver.SemVer

import munit.FunSuite

class SemVerSuite extends FunSuite {

  val expected: List[(String, SemVer.Version)] = List(
    ("3.0.0", SemVer.Version(3, 0, 0)),
    ("3.0.0-M1", SemVer.Version(3, 0, 0, milestone = Some(1))),
    ("3.0.0-RC1", SemVer.Version(3, 0, 0, releaseCandidate = Some(1))),
    (
      "3.2.0-RC1-bin-20220307-6dc591a-NIGHTLY",
      SemVer.Version(
        3,
        2,
        0,
        releaseCandidate = Some(1),
        nightlyDate = Some(20220307)
      )
    )
  )

  test("fromString") {
    val incorrect = expected
      .map { case (s, e) => (SemVer.Version.fromString(s), e) }
      .filter({ case (parsed, expected) => parsed != expected })

    assert(
      incorrect.isEmpty,
      incorrect.mkString("Failed to parse versions(expected, got):", "\n", "")
    )
  }
}
