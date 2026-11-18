package tests.mbt

import scala.meta.internal.builds.BazelProjectViewTargets
import scala.meta.internal.metals.mbt.importer.BazelQuery

import munit.FunSuite

class BazelRuleKindsQuerySuite extends FunSuite {

  private val expectedRuleKinds: String =
    "scala_library|java_library|scala_binary|java_binary|scala_test|java_test|scala_import|java_import"

  test("project-view-with-exclusions-produces-except-query") {
    val projectView =
      """|directories:
         |  .
         |
         |targets:
         |  //...:all
         |  -//excluded/...:all
         |""".stripMargin
    val patterns = BazelProjectViewTargets.parseFromContent(projectView)
    assertEquals(patterns, List("//...:all", "-//excluded/...:all"))
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"kind('$expectedRuleKinds', set(//...:all) except set(//excluded/...:all))",
    )
  }

  test("exclusions-only-defaults-includes-to-all-targets") {
    val patterns = List("-//excluded/...:all", "-//vendor/...:all")
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"kind('$expectedRuleKinds', set(//...) except set(//excluded/...:all //vendor/...:all))",
    )
  }

  test("empty-or-blank-patterns-defaults-to-all-targets") {
    assertEquals(
      BazelQuery.buildRuleKindsQuery(Nil).query,
      s"kind('$expectedRuleKinds', set(//...))",
    )
    assertEquals(
      BazelQuery.buildRuleKindsQuery(List("   ", "-")).query,
      s"kind('$expectedRuleKinds', set(//...))",
    )
  }

  test("quotes-special-characters-in-includes-and-excludes") {
    val patterns = List(
      "//core:name=value",
      "-//excluded:a1%3D=%3D%2Bagg",
    )
    assertEquals(
      BazelQuery.buildRuleKindsQuery(patterns).query,
      s"""kind('$expectedRuleKinds', set("//core:name=value") except set("//excluded:a1%3D=%3D%2Bagg"))""",
    )
  }
}
