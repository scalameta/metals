package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.mbt.importer.BazelStreamedProto
import scala.meta.internal.metals.mbt.importer.BazelTargetsProtoDump
import scala.meta.io.AbsolutePath

import tests.BaseSuite
import tests.BuildInfo

// [[BazelTargetsProtoDump]] against real `bazel query` output (rules_scala,
// Bazel 7.7.1). The fixture retains realistic noise the decoder must ignore
// (`$rule_implementation_hash`, a `--keep_going` "unknown repo" artifact).
// Regenerate with bin/regenerate-bazel-proto-fixture.sh.
class BazelTargetsProtoDumpRealOutputSuite extends BaseSuite {

  private val dump: BazelTargetsProtoDump = {
    val path = AbsolutePath(BuildInfo.testResourceDirectory)
      .resolve("bazel")
      .resolve("rules-scala-fullinfo-fragments.pb")
    new BazelTargetsProtoDump(
      BazelStreamedProto.parseRules(Files.readAllBytes(path.toNIO))
    )
  }

  // Captured targets.
  private val scalaVersion =
    "//third_party/dependency_analyzer/src/main:scala_version"
  private val reporter =
    "//src/java/io/bazel/rulesscala/scalac/reporter:reporter"
  private val depReportingCompiler =
    "//third_party/dependency_analyzer/src/main/io/bazel/rulesscala/dependencyanalyzer/compiler:dep_reporting_compiler"
  private val scalacFiles = "//src/java/io/bazel/rulesscala/scalac:scalac_files"
  private val scalac = "//src/java/io/bazel/rulesscala/scalac:scalac"
  private val scalacDependencyTest =
    "//third_party/dependency_analyzer/src/test:scalac_dependency_test"

  // Package prefixes for the source labels under test.
  private val daMain = "//third_party/dependency_analyzer/src/main"
  private val scalacPkg = "//src/java/io/bazel/rulesscala/scalac"
  private val reporterPkg = "//src/java/io/bazel/rulesscala/scalac/reporter"

  test("parses-rule-targets-and-skips-non-rule-targets") {
    assertEquals(dump.isEmpty, false)
    // The SOURCE_FILE, PACKAGE_GROUP and GENERATED_FILE lines in the fixture are
    // not rules, so they never appear; every real rule class is recognised.
    assertEquals(
      dump.ruleClassesByTarget,
      Map(
        scalaVersion -> "scala_library_for_plugin_bootstrapping",
        reporter -> "java_library",
        depReportingCompiler -> "scala_library_for_plugin_bootstrapping",
        scalacFiles -> "filegroup",
        scalac -> "java_binary",
        scalacDependencyTest -> "scala_test",
      ),
    )
  }

  test("srcs-select-groups-sources-by-scala-version") {
    // A `select_for_scala_version` over `srcs` with no `//conditions:default`:
    // the same file backs several versions (one for all 2.x, another for all
    // 3.x), so `byVersion` repeats it and `getLabels` collapses to two distinct.
    val v2 =
      s"$daMain:io/bazel/rulesscala/dependencyanalyzer/ScalaVersion.scala"
    val v3 =
      s"$daMain:io/bazel/rulesscala/dependencyanalyzer3/ScalaVersion.scala"
    val srcs = dump.srcsByTarget(scalaVersion)
    assertEquals(srcs.unconditional, Set.empty[String])
    assertEquals(
      srcs.byVersion,
      Map(
        "2.12.21" -> Set(v2),
        "2.11.12" -> Set(v2),
        "2.13.18" -> Set(v2),
        "3.1.3" -> Set(v3),
        "3.3.7" -> Set(v3),
        "3.5.2" -> Set(v3),
        "3.6.4" -> Set(v3),
        "3.7.4" -> Set(v3),
        "3.8.4" -> Set(v3),
      ),
    )
    assertEquals(dump.srcLabelsByTarget(scalaVersion), List(v2, v3))
  }

  test("srcs-select-conditions-default-is-unconditional") {
    // A `srcs` select that mixes version branches with a `//conditions:default`
    // operand: the default operand has no `scala_version` label, so its sources
    // are unconditional, while the version branches (some with several files)
    // split by version.
    val srcs = dump.srcsByTarget(reporter)
    assertEquals(
      srcs.unconditional,
      Set(
        "//src/java/io/bazel/rulesscala/scalac/deps_tracking_reporter:deps_tracking_reporter"
      ),
    )
    assertEquals(
      srcs.byVersion.keySet,
      Set("2.12.21", "2.11.12", "2.13.18", "3.1.3", "3.3.7", "3.5.2", "3.6.4",
        "3.7.4", "3.8.4"),
    )
    assertEquals(
      srcs.byVersion("2.11.12"),
      Set(s"$reporterPkg:before_2_12_13/ProtoReporter.java"),
    )
    assertEquals(
      srcs.byVersion("2.12.21"),
      Set(s"$reporterPkg:after_2_12_13_and_before_2_13_12/ProtoReporter.java"),
    )
    assertEquals(
      srcs.byVersion("3.1.3"),
      Set(
        s"$reporterPkg:scala_3/BazelConsoleReporter.java",
        s"$reporterPkg:scala_3/ProtoReporter.java",
        s"$reporterPkg:since_3_before_3_3/CompilerCompat.java",
      ),
    )
    assertEquals(
      srcs.byVersion("3.7.4"),
      Set(
        s"$reporterPkg:scala_3/BazelConsoleReporter.java",
        s"$reporterPkg:scala_3/ProtoReporter.java",
        s"$reporterPkg:since_3_7_4/CompilerCompat.java",
      ),
    )
  }

  test("filegroup-sources-are-inlined-and-deduplicated") {
    // `scalac_files` is the only filegroup; its `srcs` is itself a select (a
    // `//conditions:default` plus version branches that repeat the same files).
    assertEquals(dump.filegroupLabels, Set(scalacFiles))

    // Distinct union of every branch, in first-seen order.
    val expanded = List(
      s"$scalacPkg:ScalacInvokerResults.java",
      s"$scalacPkg:ScalacWorker.java",
      s"$scalacPkg:scala_2/ReportableMainClass.java",
      s"$scalacPkg:scala_2/ScalacInvoker.java",
      s"$scalacPkg:scala_3/ReportableDriver.java",
      s"$scalacPkg:scala_3/ScalacInvoker.java",
    )
    // The consumer `scalac` lists only the filegroup in `srcs`; it is inlined.
    assertEquals(dump.srcLabelsByTarget(scalac), expanded)
    // Querying the filegroup itself yields the same deduplicated set.
    assertEquals(dump.srcLabelsByTarget(scalacFiles), expanded)

    val fg = dump.srcsByTarget(scalacFiles)
    assertEquals(
      fg.unconditional,
      Set(
        s"$scalacPkg:ScalacInvokerResults.java",
        s"$scalacPkg:ScalacWorker.java",
      ),
    )
    assertEquals(
      fg.byVersion("2.12.21"),
      Set(
        s"$scalacPkg:scala_2/ReportableMainClass.java",
        s"$scalacPkg:scala_2/ScalacInvoker.java",
      ),
    )
    assertEquals(
      fg.byVersion("3.8.4"),
      Set(
        s"$scalacPkg:scala_3/ReportableDriver.java",
        s"$scalacPkg:scala_3/ScalacInvoker.java",
      ),
    )
  }

  test("string-list-attributes-flatten-selects-without-deduplicating") {
    // `scalacopts` is a STRING_LIST select: the 2.x branches carry no value and
    // the six 3.x branches repeat the same three opts. Unlike `getLabels`,
    // `getStrings` does NOT deduplicate, so every contributing branch is kept.
    val opts = dump.getStrings("scalacopts")(depReportingCompiler)
    assertEquals(opts.length, 18)
    assertEquals(
      opts.distinct,
      List(
        "-Wconf:msg=Alphanumeric method .* is not declared infix:s",
        "-Wconf:cat=deprecation:s",
        "-Wconf:msg=unused:s",
      ),
    )
    assertEquals(opts.count(_ == "-Wconf:cat=deprecation:s"), 6)

    // An unset STRING_LIST attribute (no value field at all) yields nothing.
    assertEquals(dump.getStrings("scalacopts")(scalaVersion), Nil)
    assertEquals(dump.getStrings("javacopts")(scalaVersion), Nil)
    // An unset STRING attribute renders as `"stringValue":""` and is dropped.
    assertEquals(dump.getStrings("scala_version")(scalaVersion), Nil)
    // A plain (non-select) STRING_LIST is returned verbatim in order.
    assertEquals(
      dump.getStrings("javacopts")(scalac),
      List("-source", "1.8", "-target", "1.8", "-Xlint:-options"),
    )
  }

  test("plain-label-list-srcs-may-be-an-external-label") {
    // `dep_reporting_compiler` takes its sources from an external repository as a
    // plain (non-select) label list, so everything is unconditional.
    assertEquals(
      dump.srcLabelsByTarget(depReportingCompiler),
      List("@scala_compiler_sources//:src"),
    )
    val srcs = dump.srcsByTarget(depReportingCompiler)
    assertEquals(srcs.unconditional, Set("@scala_compiler_sources//:src"))
    assertEquals(srcs.byVersion, Map.empty[String, Set[String]])
  }

  test("rule-outputs-are-extracted-in-order") {
    assertEquals(
      dump.ruleOutputsByTarget(reporter),
      List(s"$reporterPkg:libreporter.jar", s"$reporterPkg:libreporter-src.jar"),
    )
    assertEquals(
      dump.ruleOutputsByTarget(scalac),
      List(
        s"$scalacPkg:scalac.jar",
        s"$scalacPkg:scalac-src.jar",
        s"$scalacPkg:scalac_deploy-src.jar",
      ),
    )
    assertEquals(
      dump.ruleOutputsByTarget(scalaVersion),
      List(
        s"$daMain:scala_version.jar",
        s"$daMain:scala_version_deploy.jar",
        s"$daMain:scala_version_MANIFEST.MF",
        s"$daMain:scala_version.statsfile",
        s"$daMain:scala_version.diagnosticsproto",
        s"$daMain:scala_version.sdeps",
      ),
    )
  }

  test("deps-are-direct-but-external-deps-are-transitive-and-external-only") {
    // `depsByTarget` is the rule's direct `ruleInput` verbatim.
    val direct = dump.depsByTarget(scalac)
    assert(direct.contains(scalacFiles))
    assert(direct.contains("@bazel_tools//tools/launcher:launcher"))
    // `@rules_scala_config` is NOT a direct input of `scalac`; it is reachable
    // only through the `scalac_files` filegroup it depends on.
    assert(!direct.contains("@rules_scala_config//:scala_version_3_3_7"))

    // `externalDepsByTarget` walks the transitive closure and keeps only
    // repository-qualified labels.
    val external =
      dump.externalDepsByTarget(dump.reachableLabels(List(scalac)))(scalac)
    assert(external.forall(_.startsWith("@")))
    assert(external.contains("@bazel_tools//tools/launcher:launcher"))
    // Transitively reached: via `scalac_files` and `dep_reporting_compiler`.
    assert(external.contains("@rules_scala_config//:scala_version_3_3_7"))
    assert(external.contains("@scala_compiler_sources//:src"))
    // Internal labels are filtered out.
    assert(!external.contains(scalacFiles))
  }

  test("canonical-bzlmod-repo-labels-are-retained") {
    // Under bzlmod, `bazel query` renders some inputs with canonical (`@@`)
    // repo names rather than apparent (`@`) ones; `externalDepsByTarget` must
    // retain those exactly like apparent `@` labels.
    val external =
      dump.externalDepsByTarget(
        dump.reachableLabels(List(scalacDependencyTest))
      )(scalacDependencyTest)
    assert(
      external.exists(_.startsWith("@@")),
      s"expected a canonical @@ label among: $external",
    )
  }
}
