package scala.meta.internal.metals.mbt.importer

import java.{util => ju}

import scala.collection.mutable

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

  /**
   * Namespace holding sources from inactive `select()` branches (e.g. the
   * Scala 3 branch of a `select_for_scala_version` target when the default
   * configuration is Scala 2). Tagged with the Scala version of the branch the
   * sources came from; when several branch versions are present each gets its
   * own `<name>-<version>` namespace.
   */
  private val unconfiguredSourcesNamespaceName: String =
    "bazel-unconfigured-sources"

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
      inactiveSourceVersions: Map[String, String],
  ): MbtBuild = {
    val depModules = new ju.ArrayList[MbtDependencyModule]()
    dependencyModules.foreach(depModules.add)
    // The latest Scala version used anywhere in the project, used as a fallback
    // for real namespaces whose targets declare no version.
    val scalaVersion = scalaVersionByTarget.values.flatten.toSeq
      .maxByOption(SemVer.Version.fromString)
    // `bazel query` flattens every `select()` branch into `srcs`, so a target
    // that cross-compiles (e.g. `select_for_scala_version`) reports source
    // files of Scala versions it is not built with in the default
    // configuration. `inactiveSourceVersions` (from [[BazelBuildSrcs]]) maps
    // each such source to the Scala version of the `select()` branch it came
    // from, so they can be grouped into per-version namespaces and tagged with
    // their real Scala version (e.g. Scala 3 sources open with a Scala 3
    // compiler) regardless of which targets happen to be in import scope.
    val isInactiveSource: String => Boolean = inactiveSourceVersions.contains
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
      // Inactive sources grouped by the Scala version of the `select()` branch
      // they belong to; each group becomes its own namespace tagged with that
      // version.
      val inactiveFilesByVersion: Map[String, Set[String]] =
        srcsByTarget.values.flatten.toSet.toList
          .flatMap { (label: String) =>
            inactiveSourceVersions
              .get(label)
              .flatMap { version =>
                fileLabelToWorkspaceRelativePath(label).map(version -> _)
              }
          }
          .groupBy(_._1)
          .map { case (version, pairs) => version -> pairs.map(_._2).toSet }
      val namespaces = new ju.LinkedHashMap[String, MbtNamespace]()

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
            nsScalaVersions
              .maxByOption(SemVer.Version.fromString)
              .orElse(scalaVersion)
          putNamespace(
            namespaces,
            namespace,
            files.toSet,
            scalacOptionsByBuildFile.getOrElse(namespace, Nil),
            javacOptionsByBuildFile.getOrElse(namespace, Nil),
            dependsByNs.getOrElse(namespace, Set.empty),
            externalDepsByNs.getOrElse(namespace, Set.empty),
            runTargetsByNs.getOrElse(namespace, Set.empty),
            classDirectoriesByNs.get(namespace),
            nsScalaVersion,
          )
        }
      } else {
        val allSrcs = srcFilesByTarget.values.flatten.toSet
        val allExtDeps = externalDepsByTarget.values.flatten.toSet
        val wsScalaVersions = targetLabels
          .flatMap(scalaVersionByTarget.getOrElse(_, None))
          .distinct
        val wsScalaVersion =
          wsScalaVersions
            .maxByOption(SemVer.Version.fromString)
            .orElse(scalaVersion)
        putNamespace(
          namespaces,
          workspaceNamespaceName,
          allSrcs,
          // We don't want to merge compiler options for workspace namespaces, as it's easy to get conflicts
          Nil,
          Nil,
          Set.empty,
          allExtDeps,
          runTargetsByNs.getOrElse(workspaceNamespaceName, Set.empty),
          classDirectoriesByNs.get(workspaceNamespaceName),
          wsScalaVersion,
        )
      }
      // Keep the plain namespace name in the common single-version case; only
      // disambiguate with a `-<version>` suffix when several versions coexist.
      val singleInactiveVersion = inactiveFilesByVersion.size == 1
      for ((version, files) <- inactiveFilesByVersion.toSeq.sortBy(_._1)) {
        val namespaceName =
          if (singleInactiveVersion) unconfiguredSourcesNamespaceName
          else s"$unconfiguredSourcesNamespaceName-$version"
        putNamespace(
          namespaces,
          namespaceName,
          files,
          Nil,
          Nil,
          Set.empty,
          Set.empty,
          Set.empty,
          None,
          Some(version),
        )
      }
      MbtBuild(depModules, namespaces)
    }
  }

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
      scalacOptions: Seq[String],
      javacOptions: Seq[String],
      dependsOn: Set[String],
      dependencyModuleIds: Set[String],
      runTargets: Set[String],
      classDirectory: Option[String],
      scalaVersion: Option[String],
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
      Nil,
      Nil,
      Set.empty,
      dependencyModuleIds,
      Set.empty,
      None,
      scalaVersion,
    )
    m
  }

}
