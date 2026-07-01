package tests.bazel

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.InactiveSource
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode

import tests.BaseSuite

/**
 * Inactive `select()` branch sources (e.g. the Scala 3 branch of a
 * `select_for_scala_version` target whose default configuration is Scala 2):
 * version + origin-target recovery from `streamed_jsonproto` output
 * ([[BazelBuildSrcs.inactiveSources]]) and the per-origin
 * `<namespace>@<version>` namespaces with inherited dependencies
 * ([[BazelMbtBuildSupport.fromDiscovery]]).
 */
class BazelUnconfiguredSourcesSuite extends BaseSuite {

  private val v2_12 = "@rules_scala_config//:scala_version_2_12_21"
  private val v3_3 = "@rules_scala_config//:scala_version_3_3_0"
  private val v3_8 = "@rules_scala_config//:scala_version_3_8_3"

  private def selectRule(
      name: String,
      branches: (String, List[String])*
  ): String =
    ujson
      .Obj(
        "rule" -> ujson.Obj(
          "name" -> ujson.Str(name),
          "attribute" -> ujson.Arr(
            ujson.Obj(
              "name" -> ujson.Str("srcs"),
              "selectorList" -> ujson.Obj(
                "elements" -> ujson.Arr(
                  ujson.Obj(
                    "entries" -> ujson.Arr(
                      branches.map { case (label, srcs) =>
                        ujson.Obj(
                          "label" -> ujson.Str(label),
                          "stringListValue" -> ujson
                            .Arr(srcs.map(ujson.Str(_)): _*),
                        ): ujson.Value
                      }: _*
                    )
                  )
                )
              ),
            )
          ),
        )
      )
      .render()

  private def plainRule(name: String, srcs: List[String]): String =
    ujson
      .Obj(
        "rule" -> ujson.Obj(
          "name" -> ujson.Str(name),
          "attribute" -> ujson.Arr(
            ujson.Obj(
              "name" -> ujson.Str("srcs"),
              "stringListValue" -> ujson.Arr(srcs.map(ujson.Str(_)): _*),
            )
          ),
        )
      )
      .render()

  test("inactive-source-version-and-origin") {
    val queryOutput = selectRule(
      "//pkg/a:lib",
      v2_12 -> List("//pkg/a:A2.scala"),
      v3_8 -> List("//pkg/a:A3.scala"),
    )
    val result = BazelBuildSrcs.inactiveSources(
      queryOutput,
      Map("//pkg/a:lib" -> Some("2.12.21")),
    )
    assertEquals(
      result,
      Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-highest-version-then-smallest-target-wins") {
    val queryOutput = List(
      selectRule("//pkg/b:lib", v3_3 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/c:lib", v3_8 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
    ).mkString("\n")
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
      "//pkg/c:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(queryOutput, scalaVersions)
    assertEquals(
      result,
      Map("//pkg:Shared.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-active-in-another-target-is-excluded") {
    val queryOutput = List(
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
      plainRule("//pkg/b:lib", List("//pkg:Shared.scala")),
    ).mkString("\n")
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(queryOutput, scalaVersions)
    assertEquals(result, Map.empty[String, InactiveSource])
  }

  test("filegroup-inactive-copies-attach-to-consumer-version-branch") {
    // A source-providing filegroup cross-compiles the same class per Scala
    // version; the consumer's (flattened) srcs contain EVERY copy. The active
    // copy is inlined into the consumer's real namespace, so the inactive
    // copies follow it into the consumer's `@version` branch (not an orphan
    // filegroup namespace), keeping the branch reachable and self-contained.
    val queryOutput = List(
      selectRule(
        "//pkg/fg:fg",
        v2_12 -> List("//pkg/fg:active/A.java"),
        v3_8 -> List("//pkg/fg:scala_3/A.java"),
      ),
      plainRule("//pkg/consumer:lib", List("//pkg/consumer:Consumer.scala")),
    ).mkString("\n")
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/consumer:lib" -> Some("2.12.21"),
      "//pkg/fg:fg" -> Some("2.12.21"),
    )
    val inactive = BazelBuildSrcs.inactiveSources(queryOutput, scalaVersions)
    assertEquals(
      inactive,
      Map("//pkg/fg:scala_3/A.java" -> InactiveSource("3.8.3", "//pkg/fg:fg")),
    )

    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/consumer:lib"),
      // The xml dump inlines the filegroup's flattened srcs into the consumer.
      srcsByTarget = Map(
        "//pkg/consumer:lib" -> List(
          "//pkg/consumer:Consumer.scala",
          "//pkg/fg:active/A.java",
          "//pkg/fg:scala_3/A.java",
        )
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = scalaVersions,
      inactiveSources = inactive,
      versionSpecificSourceLabels =
        Set("//pkg/fg:active/A.java", "//pkg/fg:scala_3/A.java"),
    )
    val namespaces = build.getNamespaces.asScala
    assertEquals(
      namespaces.keySet,
      Set("//pkg/consumer", "//pkg/consumer@3.8.3"),
    )
    // The real (default-config) namespace keeps its own source plus the ACTIVE
    // copy.
    assertEquals(
      namespaces("//pkg/consumer").getSources.asScala.toList,
      List("pkg/consumer/Consumer.scala", "pkg/fg/active/A.java"),
    )
    // The Scala 3 branch carries the consumer's unconditional source plus the
    // inactive (Scala 3) filegroup copy — self-contained, so it depends on
    // nothing.
    assertEquals(
      namespaces("//pkg/consumer@3.8.3").getSources.asScala.toList,
      List("pkg/consumer/Consumer.scala", "pkg/fg/scala_3/A.java"),
    )
    assertEquals(
      namespaces("//pkg/consumer@3.8.3").getDependsOn.asScala.toList,
      List.empty,
    )
  }

  test("version-branch-namespace-uri-detection") {
    import scala.meta.internal.metals.mbt.MbtBuild
    assert(
      MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("//pkg/fg@3.8.4")
      )
    )
    assert(
      MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("//pkg@2.12.21")
      )
    )
    assert(
      !MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("//pkg/fg")
      )
    )
    // A real package that merely ends in `@<major>.<minor>` is not a synthetic
    // version branch: those always carry a full three-component Scala version.
    assert(
      !MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("//commons-io@2.11")
      )
    )
    assert(
      !MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("bazel-workspace")
      )
    )
    // An external-repo label's leading @ is not a version-branch suffix.
    assert(
      !MbtBuild.isVersionBranchNamespaceUri(
        MbtBuild.namespaceTargetId("@repo//pkg:t")
      )
    )
    // Only mbt namespace ids qualify.
    assert(!MbtBuild.isVersionBranchNamespaceUri("file:///pkg@3.8.4"))
  }

  test("version-branch-depends-on-cross-package-deps-not-its-origin") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/a:lib", "//pkg/b:lib"),
      srcsByTarget = Map(
        "//pkg/a:lib" -> List("//pkg/a:A2.scala", "//pkg/a:A3.scala"),
        "//pkg/b:lib" -> List("//pkg/b:B.scala"),
      ),
      scalacOptionsByTarget = Map("//pkg/a:lib" -> List("-deprecation")),
      javacOptionsByTarget = Map.empty,
      directDepRules = Map("//pkg/a:lib" -> List("//pkg/b:lib")),
      externalDepsByTarget = Map("//pkg/a:lib" -> List("org.foo:bar:1.0.0")),
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = Map(
        "//pkg/a:lib" -> Some("2.12.21"),
        "//pkg/b:lib" -> Some("2.12.21"),
      ),
      inactiveSources =
        Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
      versionSpecificSourceLabels = Set("//pkg/a:A2.scala", "//pkg/a:A3.scala"),
    )
    val namespaces = build.getNamespaces.asScala
    assertEquals(
      namespaces.keySet,
      Set("//pkg/a", "//pkg/b", "//pkg/a@3.8.3"),
    )

    val origin = namespaces("//pkg/a")
    assertEquals(origin.getSources.asScala.toList, List("pkg/a/A2.scala"))
    assertEquals(origin.scalaVersion, "2.12.21")

    val unconfigured = namespaces("//pkg/a@3.8.3")
    assertEquals(
      unconfigured.getSources.asScala.toList,
      List("pkg/a/A3.scala"),
    )
    assertEquals(unconfigured.scalaVersion, "3.8.3")
    // Depends on the cross-package dep `//pkg/b` (single-version, so the real
    // namespace), but NOT on its own real origin `//pkg/a`: that holds the
    // Scala 2 copy `A2.scala`, which must not land on a Scala 3 classpath. A2
    // is version-specific, so the branch has no unconditional source to carry.
    assertEquals(
      unconfigured.getDependsOn.asScala.toList,
      List("//pkg/b"),
    )
    assertEquals(
      unconfigured.getDependencyModuleIds.asScala.toList,
      List("org.foo:bar:1.0.0"),
    )
    // The origin's flags target a different Scala version — never copied.
    assertEquals(unconfigured.getScalacOptions.asScala.toList, List.empty)
  }

  test("version-branch-resolves-cross-package-deps-to-matching-branch") {
    // Mirrors rules_scala: a cross-compiled consumer (with an unconditional,
    // version-agnostic source) depends on a cross-compiled package. The Scala 3
    // branch must reach the dependency's Scala 3 branch — not its default
    // (Scala 2) namespace — and carry the consumer's unconditional source so it
    // is self-contained.
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/consumer:lib", "//pkg/dep:lib"),
      srcsByTarget = Map(
        "//pkg/consumer:lib" -> List(
          "//pkg/consumer:Shared.scala",
          "//pkg/consumer:C2.scala",
          "//pkg/consumer:C3.scala",
        ),
        "//pkg/dep:lib" -> List("//pkg/dep:D2.scala", "//pkg/dep:D3.scala"),
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map("//pkg/consumer:lib" -> List("//pkg/dep:lib")),
      externalDepsByTarget = Map.empty,
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = Map(
        "//pkg/consumer:lib" -> Some("2.12.21"),
        "//pkg/dep:lib" -> Some("2.12.21"),
      ),
      inactiveSources = Map(
        "//pkg/consumer:C3.scala" ->
          InactiveSource("3.8.3", "//pkg/consumer:lib"),
        "//pkg/dep:D3.scala" -> InactiveSource("3.8.3", "//pkg/dep:lib"),
      ),
      versionSpecificSourceLabels = Set(
        "//pkg/consumer:C2.scala",
        "//pkg/consumer:C3.scala",
        "//pkg/dep:D2.scala",
        "//pkg/dep:D3.scala",
      ),
    )
    val namespaces = build.getNamespaces.asScala
    assertEquals(
      namespaces.keySet,
      Set(
        "//pkg/consumer",
        "//pkg/dep",
        "//pkg/consumer@3.8.3",
        "//pkg/dep@3.8.3",
      ),
    )
    // Self-contained: the unconditional `Shared.scala` is carried alongside the
    // Scala 3 copy `C3.scala`.
    assertEquals(
      namespaces("//pkg/consumer@3.8.3").getSources.asScala.toList,
      List("pkg/consumer/C3.scala", "pkg/consumer/Shared.scala"),
    )
    // The Scala 3 branch reaches the dependency's Scala 3 branch, not its real
    // (Scala 2) namespace.
    assertEquals(
      namespaces("//pkg/consumer@3.8.3").getDependsOn.asScala.toList,
      List("//pkg/dep@3.8.3"),
    )
    assertEquals(
      namespaces("//pkg/dep@3.8.3").getSources.asScala.toList,
      List("pkg/dep/D3.scala"),
    )
    assertEquals(
      namespaces("//pkg/dep@3.8.3").getDependsOn.asScala.toList,
      List.empty,
    )
  }
}
