package tests.bazel

import scala.meta.internal.metals.mbt.importer.BazelTargetsProtoDump

import tests.BaseSuite

/**
 * Parsing of `bazel query --output=streamed_jsonproto
 * --proto:flatten_selects=false` output by [[BazelTargetsProtoDump]]: label /
 * string attribute flattening across `select()` branches, filegroup expansion,
 * `ruleClass`/`ruleInput`/`ruleOutput` extraction, and transitive external-dep
 * collection.
 */
class BazelTargetsProtoDumpSuite extends BaseSuite {

  private val v2_12 = "@rules_scala_config//:scala_version_2_12_21"
  private val v3_3 = "@rules_scala_config//:scala_version_3_3_0"

  private def attr(name: String, body: (String, ujson.Value)*): ujson.Value = {
    val o = ujson.Obj("name" -> ujson.Str(name))
    body.foreach { case (k, v) => o(k) = v }
    o
  }

  private def stringList(values: String*): (String, ujson.Value) =
    "stringListValue" -> ujson.Arr(values.map(ujson.Str(_)): _*)

  private def stringValue(value: String): (String, ujson.Value) =
    "stringValue" -> ujson.Str(value)

  /** A `select()` whose branches each carry a `stringListValue`. */
  private def selectBranches(
      branches: (String, List[String])*
  ): (String, ujson.Value) =
    "selectorList" -> ujson.Obj(
      "elements" -> ujson.Arr(
        ujson.Obj(
          "entries" -> ujson.Arr(
            branches.map { case (label, values) =>
              ujson.Obj(
                "label" -> ujson.Str(label),
                "stringListValue" -> ujson.Arr(values.map(ujson.Str(_)): _*),
              ): ujson.Value
            }: _*
          )
        )
      )
    )

  private def ruleLine(
      name: String,
      ruleClass: String = "scala_library",
      attributes: List[ujson.Value] = Nil,
      ruleInput: List[String] = Nil,
      ruleOutput: List[String] = Nil,
  ): String =
    ujson
      .Obj(
        "rule" -> ujson.Obj(
          "name" -> ujson.Str(name),
          "ruleClass" -> ujson.Str(ruleClass),
          "attribute" -> ujson.Arr(attributes: _*),
          "ruleInput" -> ujson.Arr(ruleInput.map(ujson.Str(_)): _*),
          "ruleOutput" -> ujson.Arr(ruleOutput.map(ujson.Str(_)): _*),
        )
      )
      .render()

  private def dump(lines: String*): BazelTargetsProtoDump =
    new BazelTargetsProtoDump(lines.mkString("\n"))

  test("get-labels-flattens-selects-and-expands-filegroups") {
    val d = dump(
      ruleLine(
        "//pkg/fg:fg",
        ruleClass = "filegroup",
        attributes = List(
          attr(
            "srcs",
            selectBranches(
              v2_12 -> List("//pkg/fg:A2.java"),
              v3_3 -> List("//pkg/fg:A3.java"),
            ),
          )
        ),
      ),
      ruleLine(
        "//pkg:lib",
        attributes =
          List(attr("srcs", stringList("//pkg:Main.scala", "//pkg/fg:fg"))),
      ),
    )

    assertEquals(d.filegroupLabels, Set("//pkg/fg:fg"))
    // The consumer's flattened srcs inline both filegroup branches.
    assertEquals(
      d.getLabels("srcs")("//pkg:lib"),
      List("//pkg:Main.scala", "//pkg/fg:A2.java", "//pkg/fg:A3.java"),
    )
    assertEquals(
      d.getLabels("srcs")("//pkg/fg:fg"),
      List("//pkg/fg:A2.java", "//pkg/fg:A3.java"),
    )
    // srcsByTarget keeps the per-branch split (feeds inactive-source analysis).
    val fgSrcs = d.srcsByTarget("//pkg/fg:fg")
    assertEquals(fgSrcs.unconditional, Set.empty[String])
    assertEquals(
      fgSrcs.byVersion,
      Map(
        "2.12.21" -> Set("//pkg/fg:A2.java"),
        "3.3.0" -> Set("//pkg/fg:A3.java"),
      ),
    )
  }

  test("get-strings-unions-select-branches-and-drops-empty-values") {
    val d = dump(
      ruleLine(
        "//pkg:a",
        attributes = List(
          attr("scalacopts", stringList("-deprecation", "-Xfatal-warnings")),
          // An unset STRING attribute renders as "stringValue":"" and is dropped.
          attr("scala_version", stringValue("")),
        ),
      ),
      ruleLine(
        "//pkg:b",
        attributes = List(
          attr(
            "scalacopts",
            selectBranches(v2_12 -> List("-Yfoo"), v3_3 -> List("-Ybar")),
          ),
          attr("scala_version", stringValue("3.3.7")),
        ),
      ),
    )

    assertEquals(
      d.getStrings("scalacopts")("//pkg:a"),
      List("-deprecation", "-Xfatal-warnings"),
    )
    assertEquals(d.getStrings("scalacopts")("//pkg:b"), List("-Yfoo", "-Ybar"))
    assertEquals(d.getStrings("scala_version")("//pkg:a"), Nil)
    assertEquals(d.getStrings("scala_version")("//pkg:b"), List("3.3.7"))
  }

  test("rule-class-output-and-input-extraction") {
    val d = dump(
      ruleLine(
        "//pkg:bin",
        ruleClass = "scala_binary",
        ruleInput = List("//pkg:lib", "@maven//:foo"),
        ruleOutput = List("//pkg:bin.jar", "//pkg:bin_deploy.jar"),
      )
    )

    assertEquals(d.ruleClassesByTarget("//pkg:bin"), "scala_binary")
    assertEquals(
      d.ruleOutputsByTarget("//pkg:bin"),
      List("//pkg:bin.jar", "//pkg:bin_deploy.jar"),
    )
    assertEquals(d.depsByTarget("//pkg:bin"), List("//pkg:lib", "@maven//:foo"))
  }

  test("is-empty-distinguishes-no-rules-from-parsed-rules") {
    // An empty / unsupported query produces no rules — the importer uses this
    // to surface the failure instead of writing an empty build silently.
    assertEquals(dump().isEmpty, true)
    assertEquals(dump("").isEmpty, true)
    // Non-rule lines (e.g. source-file targets) still leave the dump empty.
    assertEquals(
      dump(ujson.Obj("type" -> ujson.Str("SOURCE_FILE")).render()).isEmpty,
      true,
    )
    assertEquals(dump(ruleLine("//pkg:lib")).isEmpty, false)
  }

  test("external-deps-are-transitive-including-canonical-repos") {
    val d = dump(
      ruleLine("//pkg:a", ruleInput = List("//pkg:b", "@maven//:direct")),
      ruleLine(
        "//pkg:b",
        ruleInput = List("@maven//:transitive", "@@rules_jvm_external~//:x"),
      ),
    )

    // Every repository-qualified label is kept, apparent (`@`) and canonical
    // (`@@`) alike; coordinate matching in BazelMbtImporter decides what is a
    // real Maven module, so backing/internal repos are dropped there, not here.
    assertEquals(
      d.externalDepsByTarget(List("//pkg:a"))("//pkg:a"),
      List(
        "@maven//:direct",
        "@maven//:transitive",
        "@@rules_jvm_external~//:x",
      ),
    )
  }
}
