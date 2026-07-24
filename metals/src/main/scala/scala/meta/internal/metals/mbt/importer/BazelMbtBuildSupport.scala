package scala.meta.internal.metals.mbt.importer

import java.{util => ju}

import scala.collection.mutable

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.MbtNamespace

sealed abstract class BazelMbtNamespaceMode(val name: String)

object BazelMbtNamespaceMode {
  def fromName(
      name: String
  ): Option[BazelMbtNamespaceMode] =
    List(Workspace, BuildFile).find(_.name == name)

  case object Workspace extends BazelMbtNamespaceMode("workspace")
  case object BuildFile extends BazelMbtNamespaceMode("build-file")
}

/**
 * Pure construction of [[MbtBuild]] from Bazel `query` results. Kept separate
 * from process execution for unit testing.
 */
object BazelMbtBuildSupport {

  private val workspaceNamespaceName: String = "bazel-workspace"

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
      toolchain: ScalaToolchainModules.Resolution,
      genSrcOutputsByTarget: Map[String, List[String]],
      generatedProtoModules: BazelGeneratedProtoModules.Result,
  ): MbtBuild = {
    val depModules = new ju.ArrayList[MbtDependencyModule]()
    dependencyModules.foreach(depModules.add)
    // The latest Scala version used anywhere in the project, used as a fallback
    // for real namespaces whose targets declare no version.
    val scalaVersion =
      BazelScalaVersions.maxVersion(scalaVersionByTarget.values.flatten)
    if (targetLabels.isEmpty) {
      if (granularity == BazelMbtNamespaceMode.Workspace) {
        MbtBuild(
          depModules,
          singleNamespace(workspaceNamespaceName, Set.empty, scalaVersion),
          uncheckedSources = ju.Collections.emptyList(),
        )
      } else {
        MbtBuild.empty
      }
    } else {
      val (attributes, aggregates) = prepareAssembly(
        granularity,
        targetLabels,
        srcsByTarget,
        scalacOptionsByTarget,
        javacOptionsByTarget,
        directDepRules,
        externalDepsByTarget,
        runTargets,
        classDirectoriesByTarget,
        scalaVersionByTarget,
        inactiveSources,
        versionSpecificSourceLabels,
        genSrcOutputsByTarget,
        generatedProtoModules,
      )
      val namespaces = new ju.LinkedHashMap[String, MbtNamespace]()
      if (granularity == BazelMbtNamespaceMode.BuildFile)
        buildBuildFileNamespaces(
          namespaces,
          targetLabels,
          attributes,
          aggregates,
          toolchain,
          scalaVersion,
        )
      else
        buildWorkspaceNamespace(
          namespaces,
          targetLabels,
          attributes,
          aggregates,
          toolchain,
          generatedProtoModules,
          scalaVersion,
        )
      buildVersionBranchNamespaces(
        namespaces,
        attributes,
        aggregates,
        toolchain,
        generatedProtoModules,
      )
      referencedToolchainModules(
        namespaces,
        dependencyModules,
        toolchain,
        generatedProtoModules,
      ).foreach(depModules.add)
      MbtBuild(
        depModules,
        namespaces,
        uncheckedSources = ju.Collections.emptyList(),
      )
    }
  }

  private case class TargetAttributes(
      srcFilesByTarget: Map[String, List[String]],
      scalacOptionsByTarget: Map[String, List[String]],
      javacOptionsByTarget: Map[String, List[String]],
      externalDepsByTarget: Map[String, List[String]],
      genSrcOutputsByTarget: Map[String, List[String]],
      scalaVersionByTarget: Map[String, Option[String]],
  )

  /** Per-namespace aggregations derived from the target attributes. */
  private case class NamespaceAggregates(
      keys: Map[String, String],
      targetsByNs: Map[String, List[String]],
      dependsByNs: Map[String, Set[String]],
      externalDepsByNs: Map[String, Set[String]],
      generatedProtoIdsByNs: Map[String, Set[String]],
      runTargetsByNs: Map[String, Set[String]],
      classDirectoriesByNs: Map[String, List[String]],
      unconditionalFilesByNs: Map[String, Set[String]],
      branchGroups: Map[(String, String), VersionBranch],
  )

  private def prepareAssembly(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      srcsByTarget: Map[String, List[String]],
      scalacOptionsByTarget: Map[String, List[String]],
      javacOptionsByTarget: Map[String, List[String]],
      directDepRules: Map[String, List[String]],
      externalDepsByTarget: Map[String, List[String]],
      runTargets: Set[String],
      classDirectoriesByTarget: Map[String, String],
      scalaVersionByTarget: Map[String, Option[String]],
      inactiveSources: Map[String, BazelBuildSrcs.InactiveSource],
      versionSpecificSourceLabels: Set[String],
      genSrcOutputsByTarget: Map[String, List[String]],
      generatedProtoModules: BazelGeneratedProtoModules.Result,
  ): (TargetAttributes, NamespaceAggregates) = {
    val keys = targetLabels.map(t => t -> namespaceKey(granularity, t)).toMap
    val targetsByNs = targetLabels.groupBy(keys(_))
    val targetSet = targetLabels.toSet
    val dependsByNs =
      computeDependsOn(
        granularity,
        targetLabels,
        directDepRules,
        keys,
        targetSet,
      )
    val externalDepsByNs =
      computeExternalDeps(granularity, targetLabels, externalDepsByTarget, keys)
    val generatedProtoIdsByNs =
      computeNamespaceModuleIds(
        targetLabels,
        generatedProtoModules.moduleIdsByTarget,
        keys,
      )
    val runTargetsByNs =
      computeRunTargets(granularity, targetLabels, runTargets, keys)
    val classDirectoriesByNs =
      computeClassDirectories(
        targetLabels,
        runTargetsByNs,
        classDirectoriesByTarget,
        keys,
      )

    val isInactiveSource: String => Boolean = inactiveSources.contains
    val srcFilesByTarget = srcsByTarget.map { case (k, v) =>
      k -> v
        .filterNot(isInactiveSource)
        .flatMap(BazelLabels.fileLabelToWorkspaceRelativePath)
    }
    val isVersionSpecific: String => Boolean =
      versionSpecificSourceLabels.contains
    val unconditional = mutable.Map.empty[String, mutable.Set[String]]
    for {
      target <- targetLabels
      label <- srcsByTarget.getOrElse(target, Nil)
      if !isVersionSpecific(label)
      path <- BazelLabels.fileLabelToWorkspaceRelativePath(label)
    } unconditional.getOrElseUpdate(keys(target), mutable.Set.empty) += path
    val branchGroups =
      versionBranchGroups(
        granularity,
        targetLabels,
        srcsByTarget,
        inactiveSources,
      )
    val attributes = TargetAttributes(
      srcFilesByTarget = srcFilesByTarget,
      scalacOptionsByTarget = scalacOptionsByTarget,
      javacOptionsByTarget = javacOptionsByTarget,
      externalDepsByTarget = externalDepsByTarget,
      genSrcOutputsByTarget = genSrcOutputsByTarget,
      scalaVersionByTarget = scalaVersionByTarget,
    )
    val aggregates = NamespaceAggregates(
      keys = keys,
      targetsByNs = targetsByNs,
      dependsByNs = dependsByNs,
      externalDepsByNs = externalDepsByNs,
      generatedProtoIdsByNs = generatedProtoIdsByNs,
      runTargetsByNs = runTargetsByNs,
      classDirectoriesByNs = classDirectoriesByNs,
      unconditionalFilesByNs =
        unconditional.map { case (k, v) => k -> v.toSet }.toMap,
      branchGroups = branchGroups,
    )
    (attributes, aggregates)
  }

  private def buildBuildFileNamespaces(
      namespaces: ju.Map[String, MbtNamespace],
      targetLabels: List[String],
      attrs: TargetAttributes,
      agg: NamespaceAggregates,
      toolchain: ScalaToolchainModules.Resolution,
      scalaVersion: Option[String],
  ): Unit = {
    // Targets with no srcs (e.g. export-only targets) still produce a
    // namespace so their dependsOn (exports) are preserved.
    val byBuildFile = mutable.Map.empty[String, mutable.Set[String]]
    for (t <- targetLabels) {
      byBuildFile.getOrElseUpdate(agg.keys(t), mutable.Set.empty) ++=
        attrs.srcFilesByTarget.getOrElse(t, Nil)
    }
    val scalacOptionsByBuildFile =
      concatByNamespace(targetLabels, attrs.scalacOptionsByTarget, agg.keys)
    val javacOptionsByBuildFile =
      concatByNamespace(targetLabels, attrs.javacOptionsByTarget, agg.keys)
    val genSrcOutputsByNamespace =
      concatByNamespace(targetLabels, attrs.genSrcOutputsByTarget, agg.keys)
    for ((namespace, files) <- byBuildFile) {
      val targetsForNs = agg.targetsByNs.getOrElse(namespace, Nil)
      val nsScalaVersions = targetsForNs
        .flatMap(attrs.scalaVersionByTarget.getOrElse(_, None))
        .distinct
      if (nsScalaVersions.sizeIs > 1)
        scribe.warn(
          s"bazel-mbt: build-file namespace '$namespace' has active targets " +
            s"with multiple Scala versions ${nsScalaVersions.sorted.mkString(", ")}; " +
            s"analyzing all of them as ${BazelScalaVersions.maxVersion(nsScalaVersions).getOrElse("?")}. " +
            "Split mixed-version targets into separate packages for correct analysis."
        )
      val nsScalaVersion =
        BazelScalaVersions.maxVersion(nsScalaVersions).orElse(scalaVersion)
      val externalDeps = agg.externalDepsByNs.getOrElse(namespace, Set.empty)
      val protoDeps = agg.generatedProtoIdsByNs.getOrElse(namespace, Set.empty)
      putNamespace(
        namespaces,
        namespace,
        files.toSet,
        dependencyModuleIds = externalDeps ++ protoDeps ++
          toolchainIdsFor(
            toolchain,
            files,
            nsScalaVersion,
            targetsForNs,
            externalDeps,
          ),
        scalaVersion = nsScalaVersion,
        scalacOptions = scalacOptionsByBuildFile.getOrElse(namespace, Nil),
        javacOptions = javacOptionsByBuildFile.getOrElse(namespace, Nil),
        dependsOn = agg.dependsByNs.getOrElse(namespace, Set.empty),
        runTargets = agg.runTargetsByNs.getOrElse(namespace, Set.empty),
        classDirectories = agg.classDirectoriesByNs.getOrElse(namespace, Nil),
        uncheckedSources = genSrcOutputsByNamespace.getOrElse(namespace, Nil),
      )
    }
  }

  private def buildWorkspaceNamespace(
      namespaces: ju.Map[String, MbtNamespace],
      targetLabels: List[String],
      attrs: TargetAttributes,
      agg: NamespaceAggregates,
      toolchain: ScalaToolchainModules.Resolution,
      generatedProtoModules: BazelGeneratedProtoModules.Result,
      scalaVersion: Option[String],
  ): Unit = {
    val allSrcs = attrs.srcFilesByTarget.values.flatten.toSet
    val allExtDeps = attrs.externalDepsByTarget.values.flatten.toSet
    val allGenSrcOutputs = attrs.genSrcOutputsByTarget.values.flatten.toSeq
    val allProtoDeps =
      generatedProtoModules.moduleIdsByTarget.values.flatten.toSet
    val wsScalaVersions = targetLabels
      .flatMap(attrs.scalaVersionByTarget.getOrElse(_, None))
      .distinct
    val wsScalaVersion =
      BazelScalaVersions.maxVersion(wsScalaVersions).orElse(scalaVersion)
    // Compiler options are deliberately left empty for the workspace
    // namespace — merging flags from unrelated targets easily conflicts.
    putNamespace(
      namespaces,
      workspaceNamespaceName,
      allSrcs,
      dependencyModuleIds = allExtDeps ++ allProtoDeps ++
        toolchainIdsFor(
          toolchain,
          allSrcs,
          wsScalaVersion,
          targetLabels,
          allExtDeps,
        ),
      runTargets =
        agg.runTargetsByNs.getOrElse(workspaceNamespaceName, Set.empty),
      classDirectories =
        agg.classDirectoriesByNs.getOrElse(workspaceNamespaceName, Nil),
      scalaVersion = wsScalaVersion,
      uncheckedSources = allGenSrcOutputs,
    )
  }

  /** Builds the synthetic `<namespace>@<version>` branch namespaces. */
  private def buildVersionBranchNamespaces(
      namespaces: ju.Map[String, MbtNamespace],
      attrs: TargetAttributes,
      agg: NamespaceAggregates,
      toolchain: ScalaToolchainModules.Resolution,
      generatedProtoModules: BazelGeneratedProtoModules.Result,
  ): Unit =
    for (
      ((nsKey, version), branch) <-
        agg.branchGroups.toSeq.sortBy { case (group, _) => group }
    ) {
      val targetsForNs = agg.targetsByNs.getOrElse(nsKey, Nil)
      val depTargets: Set[String] = branch.originTargets ++ targetsForNs
      val externalDeps =
        depTargets.flatMap(attrs.externalDepsByTarget.getOrElse(_, Nil))
      val protoDeps =
        depTargets.flatMap(
          generatedProtoModules.moduleIdsByTarget.getOrElse(_, Set.empty)
        )
      val files =
        branch.files ++ agg.unconditionalFilesByNs.getOrElse(nsKey, Set.empty)
      val dependsOn = agg.dependsByNs.getOrElse(nsKey, Set.empty).map { dep =>
        if (agg.branchGroups.contains((dep, version))) s"$dep@$version"
        else dep
      }
      putNamespace(
        namespaces,
        s"$nsKey@$version",
        files,
        dependsOn = dependsOn,
        dependencyModuleIds = externalDeps ++ protoDeps ++
          toolchainIdsFor(
            toolchain,
            files,
            Some(version),
            depTargets,
            externalDeps,
          ),
        scalaVersion = Some(version),
      )
    }

  // Toolchain/generated-proto modules join the module list only when
  // referenced.
  private def referencedToolchainModules(
      namespaces: ju.Map[String, MbtNamespace],
      dependencyModules: Seq[MbtDependencyModule],
      toolchain: ScalaToolchainModules.Resolution,
      generatedProtoModules: BazelGeneratedProtoModules.Result,
  ): Seq[MbtDependencyModule] = {
    val referencedIds = namespaces
      .values()
      .asScala
      .flatMap(_.getDependencyModuleIds.asScala)
      .toSet
    val knownIds = dependencyModules.map(_.id).toSet
    (toolchain.modules ++ generatedProtoModules.modules)
      .filter(module => referencedIds(module.id) && !knownIds(module.id))
  }

  private def toolchainIdsFor(
      toolchain: ScalaToolchainModules.Resolution,
      files: Iterable[String],
      version: Option[String],
      targets: Iterable[String],
      existing: Set[String],
  ): Set[String] =
    if (
      files.exists(BazelSrcjarSources.isScalaBearingSource) ||
      targets.exists(toolchain.compilerClasspathTargets)
    )
      toolchain.moduleIdsFor(version, targets, existing)
    else Set.empty

  def namespaceKey(
      granularity: BazelMbtNamespaceMode,
      ruleLabel: String,
  ): String =
    if (granularity == BazelMbtNamespaceMode.Workspace) workspaceNamespaceName
    else if (granularity == BazelMbtNamespaceMode.BuildFile) {
      BazelLabels.packageKey(ruleLabel).getOrElse(ruleLabel)
    } else {
      ruleLabel
    }

  private case class VersionBranch(
      files: Set[String],
      originTargets: Set[String],
  )

  /** Groups inactive sources by (namespace key, branch version). */
  private def versionBranchGroups(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      srcsByTarget: Map[String, List[String]],
      inactiveSources: Map[String, BazelBuildSrcs.InactiveSource],
  ): Map[(String, String), VersionBranch] = {
    val groups = mutable.Map.empty[(String, String), VersionBranch]
    val consumersByLabel = mutable.Map.empty[String, mutable.ListBuffer[String]]
    for {
      target <- targetLabels
      label <- srcsByTarget.getOrElse(target, Nil)
      if inactiveSources.contains(label)
    } consumersByLabel.getOrElseUpdate(label, mutable.ListBuffer.empty) +=
      target
    for {
      label <- srcsByTarget.values.flatten.toSet[String]
      inactive <- inactiveSources.get(label)
      path <- BazelLabels.fileLabelToWorkspaceRelativePath(label)
    } {
      val consumers = consumersByLabel
        .getOrElse(label, mutable.ListBuffer.empty[String])
        .filterNot(_ == inactive.originTarget)
        .toList
      val attributedTargets =
        if (consumers.nonEmpty) consumers else List(inactive.originTarget)
      for (attributed <- attributedTargets) {
        val key = (namespaceKey(granularity, attributed), inactive.version)
        val branch = groups.getOrElse(key, VersionBranch(Set.empty, Set.empty))
        groups(key) = VersionBranch(
          branch.files + path,
          branch.originTargets + attributed,
        )
      }
    }
    groups.toMap
  }

  private def concatByNamespace(
      targetLabels: List[String],
      valuesByTarget: Map[String, List[String]],
      keys: Map[String, String],
  ): Map[String, List[String]] = {
    val acc = mutable.Map.empty[String, mutable.ListBuffer[String]]
    for {
      target <- targetLabels
      values = valuesByTarget.getOrElse(target, Nil)
      if values.nonEmpty
    } acc.getOrElseUpdate(keys(target), mutable.ListBuffer.empty) ++= values
    acc.map { case (namespace, values) => namespace -> values.toList }.toMap
  }

  private def computeDependsOn(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      directDepRules: Map[String, List[String]],
      keys: Map[String, String],
      targetSet: Set[String],
  ): Map[String, Set[String]] = {
    if (granularity == BazelMbtNamespaceMode.Workspace) {
      Map.empty
    } else {
      val outgoing = mutable.Map.empty[String, mutable.Set[String]]
      for {
        t <- targetLabels
        d <- directDepRules.getOrElse(t, Nil)
        if targetSet.contains(d)
      } {
        val fromK = keys(t)
        val toK = keys(d)
        if (fromK != toK) {
          outgoing.getOrElseUpdate(fromK, mutable.Set.empty) += toK
        }
      }
      outgoing.map { case (k, v) => k -> v.toSet }.toMap
    }
  }

  private def computeExternalDeps(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      externalDepsByTarget: Map[String, List[String]],
      keys: Map[String, String],
  ): Map[String, Set[String]] =
    if (granularity == BazelMbtNamespaceMode.Workspace) Map.empty
    else aggregateByNamespace(targetLabels, externalDepsByTarget, keys)

  /**
   * Aggregate per-target dependency-module ids to per-namespace, unioning the
   * ids of every target mapped to the same namespace key. Used for generated
   * proto jars, which (unlike external deps) are computed per consuming target
   * by [[BazelGeneratedProtoModules]].
   */
  private def computeNamespaceModuleIds(
      targetLabels: List[String],
      moduleIdsByTarget: Map[String, Set[String]],
      keys: Map[String, String],
  ): Map[String, Set[String]] =
    aggregateByNamespace(targetLabels, moduleIdsByTarget, keys)

  private def aggregateByNamespace[A](
      targetLabels: List[String],
      idsByTarget: Map[String, Iterable[A]],
      keys: Map[String, String],
  ): Map[String, Set[A]] = {
    val outgoing = mutable.Map.empty[String, mutable.Set[A]]
    for {
      target <- targetLabels
      id <- idsByTarget.getOrElse(target, Nil)
    } {
      outgoing.getOrElseUpdate(keys(target), mutable.Set.empty) += id
    }
    outgoing.map { case (k, v) => k -> v.toSet }.toMap
  }

  private def computeRunTargets(
      granularity: BazelMbtNamespaceMode,
      targetLabels: List[String],
      runTargets: Set[String],
      keys: Map[String, String],
  ): Map[String, Set[String]] = {
    val outgoing = mutable.Map.empty[String, mutable.Set[String]]
    for {
      target <- targetLabels
      if runTargets(target)
    } {
      val nsKey =
        if (granularity == BazelMbtNamespaceMode.Workspace)
          workspaceNamespaceName
        else keys(target)
      outgoing.getOrElseUpdate(nsKey, mutable.Set.empty) += target
    }
    outgoing.map { case (k, v) => k -> v.toSet }.toMap
  }

  private def computeClassDirectories(
      targetLabels: List[String],
      runTargetsByNs: Map[String, Set[String]],
      classDirectoriesByTarget: Map[String, String],
      keys: Map[String, String],
  ): Map[String, List[String]] = {
    val targetLabelsByNamespace = targetLabels.groupBy(keys)
    keys.values.toSet.flatMap { (namespace: String) =>
      val preferredTargets = runTargetsByNs.getOrElse(namespace, Set.empty)
      val fallbackTargets =
        targetLabelsByNamespace.getOrElse(namespace, Nil)
      val candidates = preferredTargets.toSeq.sorted ++ fallbackTargets
      val dirs =
        candidates.flatMap(classDirectoriesByTarget.get).distinct.toList
      if (dirs.isEmpty) None else Some(namespace -> dirs)
    }.toMap
  }

  private def putNamespace(
      namespaces: ju.Map[String, MbtNamespace],
      name: String,
      sources: Set[String],
      dependencyModuleIds: Set[String],
      scalaVersion: Option[String],
      scalacOptions: Seq[String] = Nil,
      javacOptions: Seq[String] = Nil,
      dependsOn: Set[String] = Set.empty,
      runTargets: Set[String] = Set.empty,
      classDirectories: List[String] = Nil,
      uncheckedSources: Seq[String] = Nil,
  ): Unit = {
    val sortedRunTargets =
      if (runTargets.isEmpty) null else runTargets.toSeq.sorted.asJava
    namespaces.put(
      name,
      new MbtNamespace(
        sources = sources.toSeq.sorted.asJava,
        scalacOptions = scalacOptions.distinct.asJava,
        javacOptions = javacOptions.distinct.asJava,
        dependencyModules = dependencyModuleIds.toSeq.sorted.asJava,
        scalaVersion = scalaVersion.orNull,
        javaHome = null,
        dependsOn = dependsOn.toSeq.sorted.asJava,
        classDirectories = classDirectories.asJava,
        configurations = sortedRunTargets,
        uncheckedSources =
          if (uncheckedSources.isEmpty) null
          else uncheckedSources.distinct.sorted.asJava,
      ),
    )
  }

  private def singleNamespace(
      name: String,
      dependencyModuleIds: Set[String],
      scalaVersion: Option[String],
  ): ju.Map[String, MbtNamespace] = {
    val m = new ju.LinkedHashMap[String, MbtNamespace]()
    putNamespace(
      m,
      name,
      Set.empty,
      dependencyModuleIds = dependencyModuleIds,
      scalaVersion = scalaVersion,
    )
    m
  }

}
