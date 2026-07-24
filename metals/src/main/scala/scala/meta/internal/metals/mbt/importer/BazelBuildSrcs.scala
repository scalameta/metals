package scala.meta.internal.metals.mbt.importer

import scala.collection.mutable

// Per-target Scala-version recovery from unflattened `select()` srcs.
object BazelBuildSrcs {

  case class TargetSrcs(
      unconditional: Set[String],
      byVersion: Map[String, Set[String]],
  ) {
    def activeFor(scalaVersion: Option[String]): Set[String] =
      unconditional ++ scalaVersion.flatMap(byVersion.get).getOrElse(Set.empty)
  }

  case class InactiveSource(version: String, originTarget: String)

  // Highest inactive-branch version wins; ties broken by smallest origin label.
  def inactiveSources(
      srcsByTarget: Map[String, TargetSrcs],
      scalaVersionByTarget: Map[String, Option[String]],
  ): Map[String, InactiveSource] = {
    val byTarget = srcsByTarget.filter { case (target, _) =>
      scalaVersionByTarget.contains(target)
    }
    val active = byTarget.flatMap { case (target, srcs) =>
      srcs.activeFor(scalaVersionByTarget.getOrElse(target, None))
    }.toSet
    val candidatesByLabel =
      mutable.Map.empty[String, mutable.Set[(String, String)]]
    for {
      (target, srcs) <- byTarget
      (version, srcLabels) <- srcs.byVersion
      srcLabel <- srcLabels
      if !active.contains(srcLabel)
    } candidatesByLabel.getOrElseUpdate(srcLabel, mutable.Set.empty) +=
      (version -> target)
    candidatesByLabel.flatMap { case (srcLabel, candidates) =>
      BazelScalaVersions
        .maxVersion(candidates.map { case (version, _) => version })
        .map { version =>
          val origin = candidates.collect { case (`version`, target) =>
            target
          }.min
          srcLabel -> InactiveSource(version, origin)
        }
    }.toMap
  }

  private val scalaVersionKey = """:scala_version_(\d+_\d+_\d+)""".r

  // Non-`scala_version` branches (literals, defaults) count as always compiled.
  def parseSrcs(rule: BazelRule): TargetSrcs = {
    val unconditional = mutable.Set.empty[String]
    val byVersion = mutable.Map.empty[String, mutable.Set[String]]
    for (branch <- rule.branches("srcs")) {
      branchVersion(branch.label) match {
        case Some(version) =>
          byVersion.getOrElseUpdate(
            version,
            mutable.Set.empty,
          ) ++= branch.values
        case None => unconditional ++= branch.values
      }
    }
    TargetSrcs(
      unconditional.toSet,
      byVersion.map { case (k, v) => k -> v.toSet }.toMap,
    )
  }

  private def branchVersion(label: Option[String]): Option[String] =
    label.flatMap { l =>
      scalaVersionKey.findFirstMatchIn(l).map(_.group(1).replace('_', '.'))
    }

}
