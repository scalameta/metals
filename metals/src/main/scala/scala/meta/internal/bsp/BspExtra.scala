package scala.meta.internal.bsp

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

/**
 * When Metals establish bsp-connection it sends
 *  the required semanticdb version and supportedScalaVersions (only scala2) to bsp-server
 *  to prepare compiler plugin setup on its side.
 *
 * To have the actual state of this metadata use `BspExtra.discoverNewReleases`.
 * It checks if there were scalameta/semanticdb releases that support newer Scala2 versions
 *  that were published after the Metals release.
 */
final case class BspExtra(
    semanticdbVersion: String,
    supportedScalaVersions: java.util.List[String]
)

object BspExtra {

  def apply(
      semanticdbVersion: String,
      supportedScalaVersions: Seq[String]
  ): BspExtra =
    BspExtra(semanticdbVersion, supportedScalaVersions.asJava)

  val knownAtReleaseMoment: BspExtra =
    BspExtra(
      BuildInfo.semanticdbVersion,
      BuildInfo.supportedScala2Versions
    )

  val allowedSemanticdbVersionPrefix = "4.4"

  def discoverNewReleases(): BspExtra =
    discoverNewReleases(knownAtReleaseMoment)

  def discoverNewReleases(known: BspExtra): BspExtra = {

    def semanticdbScalacPluginVersion(
        scalaVersion: String
    ): Option[SemVer.Version] = {
      val versionsQuery =
        s"org.scalameta:semanticdb-scalac_$scalaVersion:"

      val completions = coursierapi.Complete
        .create()
        .withInput(versionsQuery)
        .complete()
        .getCompletions()
        .asScala

      completions
        .filter(_.startsWith(allowedSemanticdbVersionPrefix))
        .map(SemVer.Version.fromString)
        .filter(_.isStable)
        .sortWith(_ > _)
        .headOption
    }
    // Having a known version, here we inc it's patch version and check if there
    //  is semanticdb compiler plugin artifacts for this version.
    def queryNextPatchVersions(
        prev: SemVer.Version,
        maxSemanticdbVersion: SemVer.Version,
        acc: List[String]
    ): (SemVer.Version, List[String]) = {
      val next = prev.copy(patch = prev.patch + 1)
      val pluginVersion = semanticdbScalacPluginVersion(next.toString)
      pluginVersion match {
        case Some(version) =>
          val nextMax =
            if (version > maxSemanticdbVersion) version
            else maxSemanticdbVersion

          queryNextPatchVersions(next, nextMax, next.toString :: acc)
        case None => (maxSemanticdbVersion, acc)
      }
    }

    def query(
        semanticdbVersion: SemVer.Version,
        lastMinorScalaVersion: Option[SemVer.Version]
    ): (SemVer.Version, List[String]) =
      lastMinorScalaVersion match {
        case None => (semanticdbVersion, Nil)
        case Some(last) => queryNextPatchVersions(last, semanticdbVersion, Nil)
      }

    val init = (Option.empty[SemVer.Version], Option.empty[SemVer.Version])
    val (max212, max213) =
      known.supportedScalaVersions.asScala
        .map(SemVer.Version.fromString)
        .foldLeft(init) {
          case ((curr212, curr213), v) if v.minor == 12 =>
            (curr212.filter(_ > v).orElse(Some(v)), curr213)
          case ((curr212, curr213), v) if v.minor == 13 =>
            (curr212, curr213.filter(_ > v).orElse(Some(v)))
          case (acc, _) => acc
        }

    val semanticdbVersion = SemVer.Version.fromString(known.semanticdbVersion)

    val (max212Semantic, added212) = query(semanticdbVersion, max212)
    val (max213Semantic, added213) = query(semanticdbVersion, max213)

    val finalSemanticdbVersion =
      List(semanticdbVersion, max212Semantic, max213Semantic)
        .sortWith(_ > _)
        .head
        .toString

    BspExtra(
      finalSemanticdbVersion,
      known.supportedScalaVersions.asScala.toList ++ added212 ++ added213
    )
  }
}
