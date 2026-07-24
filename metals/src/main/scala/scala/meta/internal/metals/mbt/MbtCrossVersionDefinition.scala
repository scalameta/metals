package scala.meta.internal.metals.mbt

import scala.meta.internal.metals.ScalaVersions

/**
 * Ranking for choosing among several workspace definitions of the SAME symbol
 * when a Bazel MBT import has produced per-Scala-version copies of a source.
 */
object MbtCrossVersionDefinition {

  /**
   * Facts about one candidate definition, resolved from `BuildTargets`.
   *
   * @param path the candidate file, only used as a deterministic tie-breaker
   * @param targetUris build-target uris the candidate's file belongs to
   * @param scalaVersions the Scala version declared by each of those targets
   * @param reachable whether any of those targets is in the requesting
   *   source's transitive build-target closure
   */
  final case class Candidate(
      path: String,
      targetUris: List[String],
      scalaVersions: List[String],
      reachable: Boolean,
  ) {

    /**
     * A copy that is NOT compiled by the default configuration — every target
     * it belongs to is a synthetic `<origin>@<x.y.z>` version branch.
     */
    def isVersionBranch: Boolean =
      targetUris.nonEmpty &&
        targetUris.forall(MbtBuild.isVersionBranchNamespaceUri)

    private def binaryVersions: Set[String] =
      scalaVersions.map(ScalaVersions.scalaBinaryVersionFromFullVersion).toSet

    /**
     * Sort key for this candidate, lower sorts first: version-match tier (see
     * [[VersionTier]]), then exact-full-version match (0/1), then
     * reachability through the build-target graph (0/1), then the path as a
     * deterministic tie-breaker. When the requester's version is unknown,
     * fall back to the legacy "real namespace first, then version branches"
     * order.
     */
    def rankKey(
        preferredScalaVersion: Option[String]
    ): (VersionTier.Value, Int, Int, String) = {
      val versionTier =
        if (targetUris.isEmpty) VersionTier.NoBuildTarget
        else
          preferredScalaVersion
            .map(ScalaVersions.scalaBinaryVersionFromFullVersion) match {
            case Some(want) =>
              if (binaryVersions.contains(want)) VersionTier.BinaryVersionMatch
              else if (isVersionBranch) VersionTier.OtherVersionBranch
              else VersionTier.RealNamespace
            case None =>
              if (isVersionBranch) VersionTier.RealNamespace
              else VersionTier.BinaryVersionMatch
          }
      val exactFullVersion =
        if (preferredScalaVersion.exists(scalaVersions.contains)) 0 else 1
      val reachableRank = if (reachable) 0 else 1
      (versionTier, exactFullVersion, reachableRank, path)
    }
  }

  /**
   * Coarse version-match tier for a [[Candidate]], ordered best-first:
   *   - [[VersionTier.BinaryVersionMatch]]: Scala binary version matches the
   *     requester (the right copy)
   *   - [[VersionTier.RealNamespace]]: a real (non-version-branch) namespace —
   *     what the default configuration compiles, the safe fallback when no exact
   *     copy exists (only reached when the requester's version is known)
   *   - [[VersionTier.OtherVersionBranch]]: a version branch for a different
   *     version (almost never right)
   *   - [[VersionTier.NoBuildTarget]]: no build target at all
   */
  object VersionTier extends Enumeration {
    val BinaryVersionMatch, RealNamespace, OtherVersionBranch, NoBuildTarget =
      Value
  }

  def hasVersionBranch(candidates: Seq[Candidate]): Boolean =
    candidates.exists(_.isVersionBranch)

}
