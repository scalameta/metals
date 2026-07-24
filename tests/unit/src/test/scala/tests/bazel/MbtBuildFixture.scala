package tests.bazel

import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs
import scala.meta.internal.metals.mbt.importer.BazelGeneratedProtoModules
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode
import scala.meta.internal.metals.mbt.importer.ScalaToolchainModules

object MbtBuildFixture {

  def fromDiscovery(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      srcsByTarget: Map[String, List[String]],
      scalacOptionsByTarget: Map[String, List[String]],
      javacOptionsByTarget: Map[String, List[String]],
      directDepRules: Map[String, List[String]],
      externalDepsByTarget: Map[String, List[String]],
      runTargets: Set[String],
      classDirectoriesByTarget: Map[String, String],
      dependencyModules: Seq[MbtDependencyModule],
      scalaVersionByTarget: Map[String, Option[String]],
      inactiveSources: Map[String, BazelBuildSrcs.InactiveSource],
      versionSpecificSourceLabels: Set[String],
      toolchain: ScalaToolchainModules.Resolution =
        ScalaToolchainModules.Resolution.empty,
      genSrcOutputsByTarget: Map[String, List[String]] = Map.empty,
      generatedProtoModules: BazelGeneratedProtoModules.Result =
        BazelGeneratedProtoModules.Result.empty,
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
      scalaVersionByTarget,
      inactiveSources,
      versionSpecificSourceLabels,
      toolchain,
      genSrcOutputsByTarget,
      generatedProtoModules,
    )

}
