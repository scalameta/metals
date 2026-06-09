package tests.mbt

import scala.meta.internal.metals.mbt.importer.BazelQuery

import munit.FunSuite

class BazelQuerySuite extends FunSuite {

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
