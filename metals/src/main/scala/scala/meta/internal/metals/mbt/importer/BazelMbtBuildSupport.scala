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
      scalaVersion: Option[String],
  ): MbtBuild = {
    val depModules = new ju.ArrayList[MbtDependencyModule]()
    dependencyModules.foreach(depModules.add)
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
        k -> v.flatMap(fileLabelToWorkspaceRelativePath)
      }
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
          putNamespace(
            namespaces,
            namespace,
            files.toSet,
            scalacOptionsByBuildFile.getOrElse(namespace, Nil),
            javacOptionsByBuildFile.getOrElse(namespace, Nil),
            dependsByNs.getOrElse(namespace, Set.empty),
            externalDepsByNs.getOrElse(namespace, Set.empty),
            runTargetsByNs.getOrElse(namespace, Set.empty),
            classDirectoriesByNs.getOrElse(namespace, Nil),
            scalaVersion,
          )
        }
      } else {
        val allSrcs = srcFilesByTarget.values.flatten.toSet
        val allExtDeps = externalDepsByTarget.values.flatten.toSet
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
          classDirectoriesByNs.getOrElse(workspaceNamespaceName, Nil),
          scalaVersion,
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
  ): Map[String, List[String]] =
    keys.values.toSet.flatMap { (namespace: String) =>
      val preferredTargets = runTargetsByNs.getOrElse(namespace, Set.empty)
      val fallbackTargets =
        targetLabels.filter(target => keys(target) == namespace)
      val candidates = preferredTargets.toSeq.sorted ++ fallbackTargets
      val dirs =
        candidates.flatMap(classDirectoriesByTarget.get).distinct.toList
      if (dirs.isEmpty) None else Some(namespace -> dirs)
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
      classDirectories: List[String],
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
        classDirectories = classDirectories.asJava,
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
      Nil,
      scalaVersion,
    )
    m
  }

}
