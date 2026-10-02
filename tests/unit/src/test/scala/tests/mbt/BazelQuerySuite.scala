package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelFetch
import scala.meta.internal.metals.mbt.importer.BazelQuery

import munit.FunSuite

class BazelQuerySuite extends FunSuite {

  test("fetch-batches-distinct-sorted-labels-positionally") {
    val batches = BazelFetch.batches(
      List(
        "@maven//:org_junit_jupiter_junit_jupiter_api",
        "@@rules_jvm_external++maven+maven//:org_assertj_assertj_core",
        "@maven//:org_junit_jupiter_junit_jupiter_api",
      )
    )
    assertEquals(
      batches.map(BazelFetch.commandArgs),
      List(
        List(
          "bazel", "fetch", "--keep_going",
          "@@rules_jvm_external++maven+maven//:org_assertj_assertj_core",
          "@maven//:org_junit_jupiter_junit_jupiter_api",
        )
      ),
    )
  }

  test("fetch-splits-labels-to-stay-below-the-command-length-limit") {
    val labels = (1 to 9).map(i => s"@maven//:artifact_$i").toList
    val limit = "bazel fetch --keep_going".length + 2 * 21
    val batches = BazelFetch.batches(labels, limit)
    assertEquals(batches.flatten, labels.sorted)
    assert(batches.forall(_.size == 2) || batches.last.size == 1)
    batches.foreach { batch =>
      assert(BazelFetch.commandArgs(batch).mkString(" ").length <= limit)
    }
    assertEquals(batches.size, 5)
  }

  test("parse-special-characters-bazel-query") {
    val targets = List(
      "//core:example_lib",
      "//test/name_encoding:a1%3D=%3D%2Bagg",
    )
    val query = BazelQuery.fullInformationQuery(targets)

    assertEquals(
      query.query,
      """deps(set(//core:example_lib "//test/name_encoding:a1%3D=%3D%2Bagg"))""",
    )
  }

  test("normal-labels-are-not-quoted") {
    val targets = List(
      "//foo:bar",
      "//foo/bar:baz",
      "@repo//pkg:target",
    )
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      "deps(set(//foo:bar //foo/bar:baz @repo//pkg:target))",
    )
  }

  test("external-repo-labels-with-plus-are-not-quoted") {
    val targets = List("@@rules_scala+//scala:scala")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      "deps(set(@@rules_scala+//scala:scala))",
    )
  }

  test("target-with-equals-sign-is-quoted") {
    val targets = List("//pkg:name=value")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//pkg:name=value"))""",
    )
  }

  test("target-with-plus-sign-is-quoted") {
    val targets = List("//pkg:name+value")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//pkg:name+value"))""",
    )
  }

  test("target-with-percent-sign-is-quoted") {
    val targets = List("//pkg:a1%3D")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//pkg:a1%3D"))""",
    )
  }

  test("word-starting-with-hyphen-is-quoted") {
    val targets = List("-starts_hyphen")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("-starts_hyphen"))""",
    )
  }

  test("label-with-hyphen-in-target-name-is-not-quoted") {
    val targets = List("//pkg:-target")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      "deps(set(//pkg:-target))",
    )
  }

  test("word-containing-plus-is-quoted") {
    val targets = List("//foo/bar:target+with+plus")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//foo/bar:target+with+plus"))""",
    )
  }

  test("target-with-double-quote-uses-single-quotes") {
    val targets = List("""//pkg:has"quote""")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set('//pkg:has"quote'))""",
    )
  }

  test("target-with-single-quote-uses-double-quotes") {
    val targets = List("//pkg:has'quote")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//pkg:has'quote"))""",
    )
  }

  test("target-with-both-quotes-is-skipped") {
    val targets = List(
      "//foo:ok",
      """//pkg:has"both'quotes""",
      "//bar:also_ok",
    )
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      "deps(set(//foo:ok //bar:also_ok))",
    )
  }

  test("target-with-parentheses-is-quoted") {
    val targets = List("//pkg:name(variant)")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("//pkg:name(variant)"))""",
    )
  }

  test("empty-targets-list") {
    val targets = List.empty[String]
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(query.query, "deps(set())")
  }

  test("mixed-quoted-and-unquoted-targets") {
    val targets = List(
      "//core:lib",
      "//test:a1%3D=%3D%2Bagg",
      "@repo//pkg:target",
      "//pkg:name+value",
    )
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set(//core:lib "//test:a1%3D=%3D%2Bagg" @repo//pkg:target "//pkg:name+value"))""",
    )
  }

  test("reserved-keywords") {
    val targets = List("except", "in", "intersect", "let", "set", "union")
    val query = BazelQuery.fullInformationQuery(targets)
    assertEquals(
      query.query,
      """deps(set("except" "in" "intersect" "let" "set" "union"))""",
    )
  }
}
