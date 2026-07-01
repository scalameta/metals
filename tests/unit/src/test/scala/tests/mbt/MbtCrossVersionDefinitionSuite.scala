package tests.mbt

import scala.meta.internal.metals.mbt.MbtCrossVersionDefinition
import scala.meta.internal.metals.mbt.MbtCrossVersionDefinition.Candidate

import tests.BaseSuite

/**
 * Selection among per-Scala-version copies of one symbol produced by a Bazel
 * `select_for_scala_version` cross-build (see [[MbtCrossVersionDefinition]]).
 *
 * Mirrors the real rules_scala case behind the navigation bug: the default
 * configuration is Scala 2.12, so the active `DepsTrackingReporter.java` copy
 * lives in the REAL `reporter` namespace while the `scala_3/` copy lives in a
 * synthetic `…@3.8.4` version-branch namespace that the Scala 3 caller's
 * namespace does not (transitively) depend on. Go-to-definition from the Scala
 * 3 file must still land on the Scala 3 copy.
 */
class MbtCrossVersionDefinitionSuite extends BaseSuite {

  private val ns = "mbt://namespace/"
  // The active copy is folded into the real `reporter` namespace (the
  // deps_tracking_reporter filegroup is not a compiled target of its own).
  private val realReporter =
    ns + "//src/java/io/bazel/rulesscala/scalac/reporter"

  private def branch(version: String): String =
    ns + s"//src/java/io/bazel/rulesscala/scalac/deps_tracking_reporter@$version"

  private val scala212 = Candidate(
    path =
      "deps_tracking_reporter/after_2_12_13_and_before_2_13_12/DepsTrackingReporter.java",
    targetUris = List(realReporter),
    scalaVersions = List("2.12.21"),
    reachable = true,
  )
  private val scala211 = Candidate(
    path = "deps_tracking_reporter/before_2_12_13/DepsTrackingReporter.java",
    targetUris = List(branch("2.11.12")),
    scalaVersions = List("2.11.12"),
    reachable = false,
  )
  private val scala213 = Candidate(
    path = "deps_tracking_reporter/after_2_13_12/DepsTrackingReporter.java",
    targetUris = List(branch("2.13.18")),
    scalaVersions = List("2.13.18"),
    reachable = false,
  )
  private val scala3 = Candidate(
    path = "deps_tracking_reporter/scala_3/DepsTrackingReporter.java",
    targetUris = List(branch("3.8.4")),
    scalaVersions = List("3.8.4"),
    reachable = false,
  )

  private val all = List(scala212, scala211, scala213, scala3)

  private def best(
      candidates: List[Candidate],
      preferred: Option[String],
  ): Candidate =
    MbtCrossVersionDefinition.rank(candidates, preferred).head

  test("scala-3-caller-picks-scala-3-copy-over-reachable-default") {
    // The bug: before the fix the reachable real (2.12) copy was chosen.
    assertEquals(best(all, Some("3.8.4")), scala3)
  }

  test("scala-3-caller-binary-match-ignores-patch-difference") {
    // A Scala 3.3.0 caller still resolves to the only Scala 3 copy (tagged
    // 3.8.4 by the importer's max-version convention) rather than to a Scala 2
    // copy.
    assertEquals(best(all, Some("3.3.0")), scala3)
  }

  test("active-scala-2-caller-picks-the-default-copy") {
    assertEquals(best(all, Some("2.12.21")), scala212)
  }

  test("scala-2-13-caller-picks-the-2-13-branch") {
    assertEquals(best(all, Some("2.13.18")), scala213)
  }

  test("unknown-caller-version-falls-back-to-real-namespace-first") {
    assertEquals(best(all, None), scala212)
  }

  test("real-namespace-is-fallback-when-no-matching-version-copy-exists") {
    // A Scala 3 caller, but only Scala 2 copies exist: the real (compiled)
    // copy beats the other version branch.
    val candidates = List(scala212, scala211, scala213)
    assertEquals(best(candidates, Some("3.8.4")), scala212)
  }

  test("hasVersionBranch-detects-cross-version-copies") {
    assert(MbtCrossVersionDefinition.hasVersionBranch(all))
    assert(!MbtCrossVersionDefinition.hasVersionBranch(List(scala212)))
  }

  test("non-mbt-duplicates-broken-by-reachability") {
    // Two real namespaces define the same symbol (no version branches): the
    // reachable one wins, version tiers being equal.
    val moduleA = Candidate(
      path = "a/Shared.scala",
      targetUris = List(ns + "//a"),
      scalaVersions = List("2.13.12"),
      reachable = false,
    )
    val moduleB = Candidate(
      path = "b/Shared.scala",
      targetUris = List(ns + "//b"),
      scalaVersions = List("2.13.12"),
      reachable = true,
    )
    assert(!MbtCrossVersionDefinition.hasVersionBranch(List(moduleA, moduleB)))
    assertEquals(best(List(moduleA, moduleB), Some("2.13.12")), moduleB)
  }

  test("candidate-without-build-target-sorts-last") {
    val orphan = Candidate(
      path = "z/Orphan.scala",
      targetUris = Nil,
      scalaVersions = Nil,
      reachable = false,
    )
    assertEquals(best(List(orphan, scala3), Some("3.8.4")), scala3)
  }
}
