package tests.bazel

import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.InactiveSource
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.TargetSrcs
import scala.meta.internal.metals.mbt.importer.BazelRule
import scala.meta.internal.metals.mbt.importer.BazelSelectBranch

import tests.BaseSuite

/**
 * Inactive `select()` branch sources (e.g. the Scala 3 branch of a
 * `select_for_scala_version` target whose default configuration is Scala 2):
 * version + origin-target recovery from the parsed rules
 * ([[BazelBuildSrcs.inactiveSources]]).
 */
class BazelUnconfiguredSourcesSuite extends BaseSuite {

  private val v2_12 = "@rules_scala_config//:scala_version_2_12_21"
  private val v3_3 = "@rules_scala_config//:scala_version_3_3_0"
  private val v3_8 = "@rules_scala_config//:scala_version_3_8_3"

  private def srcsOf(
      name: String,
      branches: List[BazelSelectBranch],
  ): (String, TargetSrcs) =
    name -> BazelBuildSrcs.parseSrcs(
      BazelRule(
        name,
        ruleClass = None,
        ruleInputs = Nil,
        ruleOutputs = Nil,
        attributes = Map("srcs" -> branches),
      )
    )

  private def selectRule(
      name: String,
      branches: (String, List[String])*
  ): (String, TargetSrcs) =
    srcsOf(
      name,
      branches.map { case (label, srcs) =>
        BazelSelectBranch(Some(label), srcs)
      }.toList,
    )

  private def plainRule(
      name: String,
      srcs: List[String],
  ): (String, TargetSrcs) =
    srcsOf(name, List(BazelSelectBranch(None, srcs)))

  test("inactive-source-version-and-origin") {
    val srcsByTarget = Map(
      selectRule(
        "//pkg/a:lib",
        v2_12 -> List("//pkg/a:A2.scala"),
        v3_8 -> List("//pkg/a:A3.scala"),
      )
    )
    val result = BazelBuildSrcs.inactiveSources(
      srcsByTarget,
      Map("//pkg/a:lib" -> Some("2.12.21")),
    )
    assertEquals(
      result,
      Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-highest-version-then-smallest-target-wins") {
    val srcsByTarget = Map(
      selectRule("//pkg/b:lib", v3_3 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/c:lib", v3_8 -> List("//pkg:Shared.scala")),
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
    )
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
      "//pkg/c:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(srcsByTarget, scalaVersions)
    assertEquals(
      result,
      Map("//pkg:Shared.scala" -> InactiveSource("3.8.3", "//pkg/a:lib")),
    )
  }

  test("inactive-source-active-in-another-target-is-excluded") {
    val srcsByTarget = Map(
      selectRule("//pkg/a:lib", v3_8 -> List("//pkg:Shared.scala")),
      plainRule("//pkg/b:lib", List("//pkg:Shared.scala")),
    )
    val scalaVersions: Map[String, Option[String]] = Map(
      "//pkg/a:lib" -> Some("2.12.21"),
      "//pkg/b:lib" -> Some("2.12.21"),
    )
    val result = BazelBuildSrcs.inactiveSources(srcsByTarget, scalaVersions)
    assertEquals(result, Map.empty[String, InactiveSource])
  }
}
