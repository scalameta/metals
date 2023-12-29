package scala.meta.internal.metals

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

import coursierapi.error.SimpleResolutionError
import org.jsoup.Jsoup

trait MtagsResolver {

  /**
   * Try and resolve mtags module for a given version of Scala.
   * Can contain a bunch of fallbacks in case of non stable versions.
   * @return information to use and load the presentation compiler implementation
   */
  def resolve(scalaVersion: String): Option[MtagsBinaries]

  /**
   * Check if a given Scala version is supported in Metals.
   *
   * @param version Scala version to check
   */
  def isSupportedScalaVersion(version: String): Boolean =
    resolve(version).isDefined

  /**
   * Check if this version of Scala is supported in a previous
   * binary compatible Metals version. Needed for the doctor.
   * @param version scala version to check
   */
  def isSupportedInOlderVersion(version: String): Boolean
}

object MtagsResolver {

  def default(): MtagsResolver = new Default

  /**
   * Map of removed Scala versions since 0.11.10.
   * Points to the last Metals version that supported it.
   */
  private val removedScalaVersions = Map(
    "2.13.1" -> "0.11.10",
    "2.13.2" -> "0.11.10",
    "2.13.3" -> "0.11.12",
    "2.13.4" -> "1.0.1",
    "2.12.9" -> "0.11.10",
    "2.12.10" -> "0.11.12",
    "3.0.0" -> "0.11.10",
    "3.0.1" -> "0.11.10",
    "3.0.2" -> "0.11.12",
    "3.2.2-RC1" -> "0.11.10",
    "3.3.0-RC1" -> "0.11.10",
    "3.3.0-RC2" -> "0.11.11",
    "3.3.0-RC3" -> "0.11.12",
    "3.3.0-RC4" -> "0.11.12",
    "3.3.0-RC5" -> "0.11.12",
    "3.3.0-RC6" -> "0.11.12",
    "3.3.1-RC1" -> "0.11.12",
    "3.3.1-RC2" -> "0.11.12",
    "3.3.1-RC3" -> "0.11.12",
    "3.3.1-RC4" -> "1.0.0",
    "3.3.1-RC5" -> "1.0.0",
    "3.3.1-RC6" -> "1.0.1",
    "3.3.1-RC7" -> "1.0.1",
  )

  class Default extends MtagsResolver {

    private val firstScala3PCVersion = "3.3.2-RC1-bin-20230706-3ae2dbf-NIGHTLY"
    private val states =
      new ConcurrentHashMap[String, State]()

    def isSupportedInOlderVersion(version: String): Boolean =
      removedScalaVersions.contains(version)

    def resolve(scalaVersion: String): Option[MtagsBinaries] = {
      resolve(scalaVersion, original = None)
    }

    private object ResolveType extends Enumeration {
      val Regular, StablePC, Nightly = Value
    }

    /**
     * Resolving order is following:
     * 1. Built-in mtags matching scala version
     * 2. Mtags matching exact scala version
     * 3. Stable presentation compiler matching exact scala version
     * 4. If scala version is a nightly, try to find latest supported snapshot
     */
    private def resolve(
        scalaVersion: String,
        original: Option[String],
        resolveType: ResolveType.Value = ResolveType.Regular,
    ): Option[MtagsBinaries] = {

      def fetch(tries: Int = 0): State = logResolution {
        try {
          val metalsVersion = removedScalaVersions.getOrElse(
            scalaVersion,
            BuildInfo.metalsVersion,
          )
          if (metalsVersion != BuildInfo.metalsVersion) {
            scribe.warn(
              s"$scalaVersion is no longer supported in the current Metals versions, using the last known supported version $metalsVersion"
            )
          }
          val jars = resolveType match {
            case ResolveType.StablePC =>
              Embedded.downloadScala3PresentationCompiler(scalaVersion)
            case _ => Embedded.downloadMtags(scalaVersion, metalsVersion)
          }

          State.Success(
            MtagsBinaries.Artifacts(
              scalaVersion,
              jars,
              resolveType == ResolveType.StablePC,
            )
          )
        } catch {
          case NonFatal(e) =>
            State.Failure(System.currentTimeMillis(), tries, e)
        }
      }
      def shouldResolveAgain(failure: State.Failure): Boolean = {
        failure.tries < State.maxTriesInARow ||
        (System
          .currentTimeMillis() - failure.lastTryMillis) > 5.minutes.toMillis
      }

      def logResolution(state: State): State = {
        state match {
          case _: State.Success =>
            val msg = resolveType match {
              case ResolveType.Regular => s"Resolved mtags for $scalaVersion"
              case ResolveType.StablePC =>
                s"Resolved Scala 3 presentation compiler for $scalaVersion"
              case ResolveType.Nightly =>
                s"Resolved latest nightly mtags version: $scalaVersion"
            }
            scribe.debug(msg)
          case _: State.Failure =>
            val errorMsg = resolveType match {
              case ResolveType.Regular =>
                s"Failed to resolve mtags for $scalaVersion"
              case ResolveType.StablePC =>
                s"Failed to resolve Scala 3 presentation compiler for $scalaVersion"
              case ResolveType.Nightly =>
                s"Failed to resolve latest nightly mtags version: $scalaVersion"
            }
            scribe.info(errorMsg)
        }
        state
      }

      // The metals_2.12 artifact depends on mtags_2.12.x where "x" matches
      // `mtags.BuildInfo.scalaCompilerVersion`. In the case when
      // `info.getScalaVersion == mtags.BuildInfo.scalaCompilerVersion` then we
      // skip fetching the mtags module from Maven.
      if (MtagsBinaries.isBuildIn(scalaVersion)) {
        Some(MtagsBinaries.BuildIn)
      } else {
        val computed = states.compute(
          original.getOrElse(scalaVersion),
          (_, value) => {
            value match {
              case null => fetch()
              case succ: State.Success => succ
              case failure: State.Failure =>
                if (shouldResolveAgain(failure))
                  fetch(failure.tries + 1)
                else {
                  failure
                }
            }
          },
        )

        def logError(e: Throwable): Unit = {
          val msg = s"Failed to fetch mtags for ${scalaVersion}"
          e match {
            case _: SimpleResolutionError =>
              // no need to log traces for coursier error
              // all explanation is in message
              scribe.error(msg + "\n" + e.getMessage())
            case _ =>
              scribe.error(msg, e)
          }
        }

        computed match {
          case State.Success(v) =>
            Some(v)
          // Fallback to Stable PC version
          case _: State.Failure
              if resolveType != ResolveType.StablePC &&
                SemVer.isCompatibleVersion(
                  firstScala3PCVersion,
                  scalaVersion,
                ) =>
            resolve(
              scalaVersion,
              None,
              ResolveType.StablePC,
            )
          // Try to download latest supported snapshot
          case _: State.Failure
              if resolveType != ResolveType.Nightly &&
                scalaVersion.contains("NIGHTLY") ||
                scalaVersion.contains("nonbootstrapped") =>
            findLatestSnapshot(scalaVersion) match {
              case None => None
              case Some(latestSnapshot) =>
                scribe.warn(s"Using latest stable version $latestSnapshot")
                resolve(
                  latestSnapshot,
                  Some(scalaVersion),
                  ResolveType.Nightly,
                )
            }
          case failure: State.Failure =>
            logError(failure.exception)
            None
          case _ => None
        }
      }
    }

    /**
     * Nightlies version are able to work with artifacts compiled within the
     * same RC version.
     *
     * For example 3.2.2-RC1-bin-20221009-2052fc2-NIGHTLY presentation compiler
     * will work with classfiles compiled with 3.2.2-RC1-bin-20220910-ac6cd1c-NIGHTLY
     *
     * @param exactVersion version we failed to find and looking for an alternative for
     * @return latest supported nightly version by thise version of metals
     */
    private def findLatestSnapshot(exactVersion: String): Option[String] = try {

      val metalsVersion = BuildInfo.metalsVersion

      // strip timestamp to get only 3.2.2-RC1
      val rcVersion = SemVer.Version
        .fromString(exactVersion)
        .copy(nightlyDate = None)
        .toString()

      val url =
        s"https://oss.sonatype.org/content/repositories/snapshots/org/scalameta/"

      val allScalametaArtifacts = Jsoup.connect(url).get

      // find all the nightlies for current RC
      val lastNightlies = allScalametaArtifacts
        .select("a")
        .asScala
        .filter { a =>
          val name = a.text()
          name.contains("NIGHTLY") && name.contains(rcVersion)
        }

      // find last supported Scala version for this metals version
      lastNightlies.reverseIterator
        .find { nightlyLink =>
          val link = nightlyLink.attr("href")

          val mtagsPage = Jsoup.connect(link).get

          mtagsPage
            .select("a")
            .asScala
            .find(_.text() == metalsVersion + "/")
            .isDefined
        }
        .map(_.text().stripPrefix("mtags_").stripSuffix("/"))

    } catch {
      case NonFatal(t) =>
        scribe.error("Could not check latest nightlies", t)
        None
    }

    sealed trait State
    object State {
      val maxTriesInARow: Int = 2
      case class Success(v: MtagsBinaries.Artifacts) extends State
      case class Failure(lastTryMillis: Long, tries: Int, exception: Throwable)
          extends State
    }
  }

}
