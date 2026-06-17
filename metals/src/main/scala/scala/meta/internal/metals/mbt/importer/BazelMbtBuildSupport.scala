package scala.meta.internal.metals.mbt.importer

import java.{util => ju}

import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.MbtNamespace
import scala.meta.internal.semver.SemVer

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
      toolchain: ScalaToolchainModules.Resolution =
        ScalaToolchainModules.Resolution.empty,
  ): MbtBuild = {
    val depModules = new ju.ArrayList[MbtDependencyModule]()
    dependencyModules.foreach(depModules.add)
    // The latest Scala version used anywhere in the project, used as a fallback
    // for real namespaces whose targets declare no version.
    val scalaVersion = maxVersion(scalaVersionByTarget.values.flatten)
    // `bazel query` flattens every `select()` branch into `srcs`, so a target
    // that cross-compiles (e.g. `select_for_scala_version`) reports source
    // files of Scala versions it is not built with in the default
    // configuration. `inactiveSources` (from [[BazelBuildSrcs]]) maps each
    // such source to the Scala version of the `select()` branch it came from
    // and to the target declaring it, so they can be grouped into namespaces
    // mirroring the configuration Bazel would actually compile them in — the
    // origin target built with that Scala version — tagged with their real
    // Scala version (e.g. Scala 3 sources open with a Scala 3 compiler)
    // regardless of which targets happen to be in import scope.
    val isInactiveSource: String => Boolean = inactiveSources.contains
    if (targetLabels.isEmpty) {
      if (granularity == BazelMbtNamespaceMode.Workspace) {
        MbtBuild(
          depModules,
          singleNamespace(workspaceNamespaceName, Set.empty, scalaVersion),
        )
      } else {
        MbtBuild.empty
      }
    } else {
      val keys = targetLabels.map(t => t -> namespaceKey(granularity, t)).toMap
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
        computeExternalDeps(
          granularity,
          targetLabels,
          externalDepsByTarget,
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
      val srcFilesByTarget = srcsByTarget.map { case (k, v) =>
        k -> v
          .filterNot(isInactiveSource)
          .flatMap(
            fileLabelToWorkspaceRelativePath
          )
      }
      // A source label that appears in a `select_for_scala_version` branch
      // (active OR inactive). A source NOT in this set is version-agnostic
      // ("unconditional"): the build compiles it in every configuration, so
      // each version branch carries its own copy (below) rather than depending
      // on the real origin — which also holds the default configuration's
      // version-specific copies that would clash on the branch's classpath.
      val isVersionSpecific: String => Boolean =
        versionSpecificSourceLabels.contains
      val unconditionalFilesByNs =
        mutable.Map.empty[String, mutable.Set[String]]
      for {
        target <- targetLabels
        label <- srcsByTarget.getOrElse(target, Nil)
        if !isVersionSpecific(label)
        path <- fileLabelToWorkspaceRelativePath(label)
      } unconditionalFilesByNs
        .getOrElseUpdate(keys(target), mutable.Set.empty) += path
      // Inactive sources grouped by (namespace, branch version); each group
      // becomes a version-branch namespace modelling the configuration in which
      // Bazel would actually compile those sources.
      //
      // A source-providing filegroup's ACTIVE copy is inlined into every
      // consuming target's `srcs` by `getLabels`; mirror that for the INACTIVE
      // copies so they land in the consumer's version branch — where they stay
      // reachable and version-correct — instead of an orphan filegroup
      // namespace nothing depends on. A source whose only home is the target
      // that declares the branch (origin == consumer) keeps its own branch.
      val inactiveFilesByGroup =
        mutable.Map.empty[(String, String), mutable.Set[String]]
      val inactiveOriginsByGroup =
        mutable.Map.empty[(String, String), mutable.Set[String]]
      for {
        label <- srcsByTarget.values.flatten.toSet[String]
        inactive <- inactiveSources.get(label)
        path <- fileLabelToWorkspaceRelativePath(label)
      } {
        val consumers = targetLabels.filter(consumer =>
          consumer != inactive.originTarget &&
            srcsByTarget.getOrElse(consumer, Nil).contains(label)
        )
        val attributedTargets =
          if (consumers.nonEmpty) consumers else List(inactive.originTarget)
        for (attributed <- attributedTargets) {
          val group = (namespaceKey(granularity, attributed), inactive.version)
          inactiveFilesByGroup.getOrElseUpdate(group, mutable.Set.empty) += path
          inactiveOriginsByGroup.getOrElseUpdate(group, mutable.Set.empty) +=
            attributed
        }
      }
      val namespaces = new ju.LinkedHashMap[String, MbtNamespace]()
      // Bazel's Scala toolchain enters the compile classpath of scala rules
      // only — a namespace without Scala sources (e.g. java_library targets,
      // whose scalaVersion is just the project-wide fallback) gets no
      // toolchain modules.
      def toolchainIdsFor(
          files: Iterable[String],
          version: Option[String],
          targets: Iterable[String],
          existing: Set[String],
      ): Set[String] =
        if (files.exists(_.endsWith(".scala")))
          toolchain.moduleIdsFor(version, targets, existing)
        else Set.empty

      if (granularity == BazelMbtNamespaceMode.BuildFile) {
        val byBuildFile = mutable.Map.empty[String, mutable.Set[String]]
        val scalacOptionsByBuildFile = mutable.Map.empty[String, List[String]]
        val javacOptionsByBuildFile = mutable.Map.empty[String, List[String]]
        for {
          t <- targetLabels
          p = keys(t)
          f <- srcFilesByTarget.getOrElse(t, Nil)
        } {
          byBuildFile.getOrElseUpdate(p, mutable.Set.empty) += f
        }
        // Ensure targets with no srcs (e.g. export-only targets) still produce
        // a namespace so their dependsOn (exports) are preserved.
        for (t <- targetLabels) {
          byBuildFile.getOrElseUpdate(keys(t), mutable.Set.empty)
        }
        for {
          t <- targetLabels
          p = keys(t)
          scalacOptions = scalacOptionsByTarget.getOrElse(t, Nil)
          if scalacOptions.nonEmpty
        } {
          scalacOptionsByBuildFile.update(
            p,
            scalacOptionsByBuildFile.getOrElse(p, Nil) ++ scalacOptions,
          )
        }
        for {
          t <- targetLabels
          p = keys(t)
          javacOptions = javacOptionsByTarget.getOrElse(t, Nil)
          if javacOptions.nonEmpty
        } {
          javacOptionsByBuildFile.update(
            p,
            javacOptionsByBuildFile.getOrElse(p, Nil) ++ javacOptions,
          )
        }
        for ((namespace, files) <- byBuildFile) {
          val targetsForNs = targetLabels.filter(keys(_) == namespace)
          val nsScalaVersions = targetsForNs
            .flatMap(scalaVersionByTarget.getOrElse(_, None))
            .distinct
          val nsScalaVersion =
            maxVersion(nsScalaVersions).orElse(scalaVersion)
          val externalDeps = externalDepsByNs.getOrElse(namespace, Set.empty)
          putNamespace(
            namespaces,
            namespace,
            files.toSet,
            dependencyModuleIds = externalDeps ++
              toolchainIdsFor(
                files,
                nsScalaVersion,
                targetsForNs,
                externalDeps,
              ),
            scalaVersion = nsScalaVersion,
            scalacOptions = scalacOptionsByBuildFile.getOrElse(namespace, Nil),
            javacOptions = javacOptionsByBuildFile.getOrElse(namespace, Nil),
            dependsOn = dependsByNs.getOrElse(namespace, Set.empty),
            runTargets = runTargetsByNs.getOrElse(namespace, Set.empty),
            classDirectory = classDirectoriesByNs.get(namespace),
          )
        }
      } else {
        val allSrcs = srcFilesByTarget.values.flatten.toSet
        val allExtDeps = externalDepsByTarget.values.flatten.toSet
        val wsScalaVersions = targetLabels
          .flatMap(scalaVersionByTarget.getOrElse(_, None))
          .distinct
        val wsScalaVersion =
          maxVersion(wsScalaVersions).orElse(scalaVersion)
        // Compiler options are deliberately left empty for the workspace
        // namespace — merging flags from unrelated targets easily conflicts.
        putNamespace(
          namespaces,
          workspaceNamespaceName,
          allSrcs,
          dependencyModuleIds = allExtDeps ++
            toolchainIdsFor(allSrcs, wsScalaVersion, targetLabels, allExtDeps),
          runTargets =
            runTargetsByNs.getOrElse(workspaceNamespaceName, Set.empty),
          classDirectory = classDirectoriesByNs.get(workspaceNamespaceName),
          scalaVersion = wsScalaVersion,
        )
      }
      // Named `<namespace>@<version>` — names are opaque to every consumer
      // (they only become `mbt://namespace/<name>` ids). The `@<version>`
      // suffix is a full three-component Scala version, which
      // `MbtBuild.isVersionBranchNamespaceUri` keys on to recognise these
      // synthetic namespaces (so a real key ending in `@<major>.<minor>` is not
      // mistaken for one).
      //
      // The branch is SELF-CONTAINED: it carries the namespace's version-
      // agnostic (unconditional) sources alongside its version-specific ones,
      // so it does NOT depend on its real origin (whose default-configuration
      // copies are a different Scala version and would clash on the classpath).
      // Cross-package dependencies resolve to the matching `@<version>` branch
      // when one exists, and to the real (single-version) namespace otherwise.
      // It inherits the external modules of the contributing targets;
      // scalacOptions are deliberately NOT copied — the origin's flags target a
      // different Scala version.
      val branchGroupKeys: Set[(String, String)] =
        inactiveFilesByGroup.keysIterator.toSet
      for (
        ((nsKey, version), branchFiles) <-
          inactiveFilesByGroup.toSeq.sortBy { case (group, _) => group }
      ) {
        val targetsForNs = targetLabels.filter(keys(_) == nsKey)
        val depTargets: Set[String] =
          inactiveOriginsByGroup(nsKey -> version).toSet ++ targetsForNs
        val externalDeps =
          depTargets.flatMap(externalDepsByTarget.getOrElse(_, Nil))
        val files =
          branchFiles.toSet ++
            unconditionalFilesByNs.getOrElse(nsKey, mutable.Set.empty)
        val dependsOn = dependsByNs.getOrElse(nsKey, Set.empty).map { dep =>
          if (branchGroupKeys.contains((dep, version))) s"$dep@$version"
          else dep
        }
        putNamespace(
          namespaces,
          s"$nsKey@$version",
          files,
          dependsOn = dependsOn,
          dependencyModuleIds = externalDeps ++
            toolchainIdsFor(files, Some(version), depTargets, externalDeps),
          scalaVersion = Some(version),
        )
      }
      // Toolchain modules join the build's module list only when some
      // namespace actually references them — a workspace whose stdlib is
      // already pinned through `@maven//` keeps an unchanged module list.
      val referencedIds = namespaces
        .values()
        .asScala
        .flatMap(_.getDependencyModuleIds.asScala)
        .toSet
      val knownIds = dependencyModules.map(_.id).toSet
      toolchain.modules
        .filter(module => referencedIds(module.id) && !knownIds(module.id))
        .foreach(depModules.add)
      MbtBuild(depModules, namespaces)
    }
  }

  /**
   * The highest of `versions` that parses as a Scala/SemVer version, logging
   * and skipping any that do not. `scala_version` is a free-form Bazel `STRING`
   * attribute, so a single non-numeric value (e.g. `scala_version = "scala3"`
   * on a custom rule anywhere in the transitive closure) must not abort the
   * whole import — which `versions.maxByOption(SemVer.Version.fromString)`
   * would, because `fromString` throws on a non-numeric leading component and
   * `maxByOption` evaluates the key for every element.
   */
  def maxVersion(versions: Iterable[String]): Option[String] =
    versions
      .flatMap { version =>
        Try(SemVer.Version.fromString(version)) match {
          case Success(parsed) => Some(version -> parsed)
          case Failure(_) =>
            scribe.error(
              s"bazel-mbt: could not parse Scala version '$version'; ignoring it"
            )
            None
        }
      }
      .maxByOption { case (_, parsed) => parsed }
      .map { case (version, _) => version }

  def namespaceKey(
      granularity: BazelMbtNamespaceMode,
      ruleLabel: String,
  ): String =
    if (granularity == BazelMbtNamespaceMode.Workspace) workspaceNamespaceName
    else if (granularity == BazelMbtNamespaceMode.BuildFile) {
      packageKey(ruleLabel).getOrElse(ruleLabel)
    } else {
      ruleLabel
    }

  def packageKey(ruleLabel: String): Option[String] = {
    val s = ruleLabel.trim
    if (!s.startsWith("//")) None
    else {
      val rest = s.substring(2)
      val c = rest.lastIndexOf(':')
      if (c < 0) None
      else Some("//" + rest.substring(0, c))
    }
  }

  /**
   * Map a Bazel file label `//path/to:File.ext` to a workspace-relative path
   * `path/to/File.ext`.
   */
  def fileLabelToWorkspaceRelativePath(fileLabel: String): Option[String] = {
    val s = fileLabel.trim
    if (!s.startsWith("//")) None
    else {
      val rest = s.substring(2)
      val c = rest.lastIndexOf(':')
      if (c < 0) None
      else {
        val pkg = rest.substring(0, c)
        val name = rest.substring(c + 1)
        if (name.isEmpty) None
        else Some(s"$pkg/$name")
      }
    }
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
  ): Map[String, Set[String]] = {
    if (granularity == BazelMbtNamespaceMode.Workspace) {
      Map.empty
    } else {
      val outgoing = mutable.Map.empty[String, mutable.Set[String]]
      for {
        t <- targetLabels
        moduleId <- externalDepsByTarget.getOrElse(t, Nil)
      } {
        val nsKey = keys(t)
        outgoing.getOrElseUpdate(nsKey, mutable.Set.empty) += moduleId
      }
      outgoing.map { case (k, v) => k -> v.toSet }.toMap
    }
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
  ): Map[String, String] =
    keys.values.toSet.flatMap { (namespace: String) =>
      val preferredTargets = runTargetsByNs.getOrElse(namespace, Set.empty)
      val fallbackTargets =
        targetLabels.filter(target => keys(target) == namespace)
      val candidates = preferredTargets.toSeq.sorted ++ fallbackTargets
      candidates
        .flatMap(classDirectoriesByTarget.get)
        .headOption
        .map(dir => namespace -> dir)
    }.toMap

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
      classDirectory: Option[String] = None,
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
        classDirectories = classDirectory.toList.asJava,
        configurations = sortedRunTargets,
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
