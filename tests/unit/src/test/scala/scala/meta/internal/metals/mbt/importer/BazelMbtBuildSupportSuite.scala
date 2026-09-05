package scala.meta.internal.metals.mbt.importer

import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.io.RelativePath

import munit.FunSuite

class BazelMbtBuildSupportSuite extends FunSuite {

  test("redundant-targets-for-workspace-mode") {

    val mbtBuild = fromDiscovery(
      BazelMbtNamespaceMode.Workspace,
      targetLabels = List("foo"),
      srcsByTarget = Map(
        "foo" -> List(RelativePath("Source.java")),
        "bar" -> List(RelativePath("BadSource.js")),
      ),
    )

    val expectedMbtJson: String =
      """|{
         |  "dependencyModules": [],
         |  "namespaces": {
         |    "bazel-workspace": {
         |      "sources": [
         |        "Source.java"
         |      ],
         |      "scalacOptions": [],
         |      "javacOptions": [],
         |      "dependencyModules": [],
         |      "dependsOn": [],
         |      "classDirectories": []
         |    }
         |  },
         |  "uncheckedSources": []
         |}
         |""".stripMargin

    assertEquals(MbtBuild.toJson(mbtBuild).trim(), expectedMbtJson.trim())
  }

  test("compiler-options-for-workspace-mode") {
    val mbtBuild = fromDiscovery(
      BazelMbtNamespaceMode.Workspace,
      targetLabels = List("foo"),
      scalacOptionsByTarget = Map(
        "foo" -> List("-opt")
      ),
      javacOptionsByTarget = Map(
        "foo" -> List("-opt2")
      ),
    )

    val expectedMbtJson: String =
      """|{
         |  "dependencyModules": [],
         |  "namespaces": {
         |    "bazel-workspace": {
         |      "sources": [],
         |      "scalacOptions": [],
         |      "javacOptions": [],
         |      "dependencyModules": [],
         |      "dependsOn": [],
         |      "classDirectories": []
         |    }
         |  },
         |  "uncheckedSources": []
         |}
         |""".stripMargin

    assertEquals(MbtBuild.toJson(mbtBuild).trim(), expectedMbtJson.trim())
  }

  private def fromDiscovery(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String] = Nil,
      srcsByTarget: Map[String, List[RelativePath]] = Map.empty,
      scalacOptionsByTarget: Map[String, List[String]] = Map.empty,
      javacOptionsByTarget: Map[String, List[String]] = Map.empty,
      directDepRules: Map[String, List[String]] = Map.empty,
      externalDepsByTarget: Map[String, List[String]] = Map.empty,
      runTargets: Set[String] = Set.empty,
      classDirectoriesByTarget: Map[String, String] = Map.empty,
      dependencyModules: Seq[MbtDependencyModule] = Seq.empty,
      scalaVersion: Option[String] = None,
      genSrcOutputsByTarget: Map[String, List[String]] = Map.empty,
  ): MbtBuild =
    BazelMbtBuildSupport.fromDiscovery(
      granularity,
      targetLabels,
      srcsByTarget,
      scalacOptionsByTarget,
      javacOptionsByTarget,
      directDepRules,
      externalDepsByTarget,
      runTargets,
      classDirectoriesByTarget,
      dependencyModules,
      scalaVersion,
      genSrcOutputsByTarget,
    )

}
