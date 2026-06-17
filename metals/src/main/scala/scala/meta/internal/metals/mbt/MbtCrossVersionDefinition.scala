package scala.meta.internal.metals.mbt

import scala.meta.internal.metals.ScalaVersions

/**
 * Ranking for choosing among several workspace definitions of the SAME symbol
 * when a Bazel MBT import has produced per-Scala-version copies of a source.
 *
 * A target that cross-compiles via `select_for_scala_version(...)` declares one
 * source per Scala version (e.g. a `scala_3/` copy and a `before_2_12_13/` copy
 * of the same Java class). The importer keeps the copy of the inactive branch
 * in an `<origin>@<x.y.z>` "version-branch" namespace (recognised by
 * [[MbtBuild.isVersionBranchNamespaceUri]]); the copy compiled by the default
 * configuration lives in the real namespace. Every copy is indexed, so a single
 * symbol resolves to several files.
 *
 * Go-to-definition from a Scala 3 file must land on the Scala 3 copy, not on
 * whichever copy the default Bazel configuration happens to compile. The
 * requesting source's Scala version is the disambiguator; reachability through
 * the build-target graph only breaks ties between otherwise equal copies (e.g.
 * the same class genuinely duplicated across two real modules).
 *
 * This is intentionally pure (no `BuildTargets`) so it is cheap to unit-test;
 * the caller resolves the [[Candidate]] facts from `BuildTargets`.
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
     * Sort key, lower sorts first. Version tiers, in order:
     *   - 0: Scala binary version matches the requester (the right copy)
     *   - 1: a real (non-version-branch) namespace — what the default
     *        configuration compiles, the safe fallback when no exact copy
     *        exists (only reached when the requester's version is known)
     *   - 2: a version branch for a different version (almost never right)
     *   - 3: no build target at all
     *
     * Within a tier, prefer an exact full-version match, then a reachable copy,
     * then order by path. When the requester's version is unknown, fall back to
     * the legacy "real namespace first, then version branches" order.
     */
    def rankKey(
        preferredScalaVersion: Option[String]
    ): (Int, Int, Int, String) = {
      val versionTier =
        if (targetUris.isEmpty) 3
        else
          preferredScalaVersion
            .map(ScalaVersions.scalaBinaryVersionFromFullVersion) match {
            case Some(want) =>
              if (binaryVersions.contains(want)) 0
              else if (!isVersionBranch) 1
              else 2
            case None =>
              if (!isVersionBranch) 0 else 1
          }
      val exactFullVersion =
        if (preferredScalaVersion.exists(scalaVersions.contains)) 0 else 1
      val reachableRank = if (reachable) 0 else 1
      (versionTier, exactFullVersion, reachableRank, path)
    }
  }

  /** Whether any candidate is a per-version copy needing version-aware ranking. */
  def hasVersionBranch(candidates: Seq[Candidate]): Boolean =
    candidates.exists(_.isVersionBranch)

  /**
   * `candidates` ordered best-first for a requester compiled with
   * `preferredScalaVersion`. A stable sort, so equally ranked copies keep their
   * input order (the path component of the key keeps it deterministic anyway).
   */
  def rank(
      candidates: Seq[Candidate],
      preferredScalaVersion: Option[String],
  ): Seq[Candidate] =
    candidates.sortBy(_.rankKey(preferredScalaVersion))
}
