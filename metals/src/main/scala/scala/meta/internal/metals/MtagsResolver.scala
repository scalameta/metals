package scala.meta.internal.metals

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.semver.SemVer

trait MtagsResolver {

  /**
   * Try and resolve mtags module for a given version of Scala.
   * @return information to use and load the presentation compiler implementation
   */
  def resolve(scalaVersion: String)(implicit
      ec: ExecutionContext
  ): Option[MtagsBinaries]

  /**
   * Check if a given Scala version is supported in Metals.
   *
   * @param version Scala version to check
   */
  def isSupportedScalaVersion(version: String)(implicit
      ec: ExecutionContext
  ): Boolean =
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
  val removedScalaVersions: Map[String, String] = Map(
    "2.13.1" -> "0.11.10",
    "2.13.2" -> "0.11.10",
    "2.13.3" -> "0.11.12",
    "2.13.4" -> "1.0.1",
    "2.13.5" -> "1.2.2",
    "2.13.6" -> "1.3.0",
    "2.12.9" -> "0.11.10",
    "2.12.10" -> "0.11.12",
    "2.12.11" -> "1.2.2",
    "3.0.0" -> "0.11.10",
    "3.0.1" -> "0.11.10",
    "3.0.2" -> "0.11.12",
    "3.1.0" -> "1.3.0",
    "3.1.1" -> "1.3.0",
    "3.1.2" -> "1.3.0",
    "3.2.0" -> "1.3.0",
    "3.2.1" -> "1.3.0",
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
    "3.3.0" -> "1.2.2",
    "3.3.2-RC1" -> "1.2.2",
    "3.3.2-RC2" -> "1.2.2",
    "3.3.2-RC3" -> "1.2.2",
    "3.1.3" -> "1.3.2",
    "2.12.12" -> "1.3.3",
    "2.12.13" -> "1.3.3",
    "2.12.14" -> "1.3.3",
    "2.12.15" -> "1.3.3",
    "2.12.16" -> "1.3.5",
    "2.13.7" -> "1.3.3",
    "2.13.8" -> "1.3.3",
    "2.13.9" -> "1.3.3",
    "2.13.10" -> "1.3.3",
    "2.13.11" -> "1.3.5",
    "3.2.2" -> "1.3.5",
    "3.3.2" -> "1.3.5",
  )

  class Default extends MtagsResolver {

    private val firstScala3PCVersion = "3.3.4-RC1"
    private val states =
      new ConcurrentHashMap[String, State]()

    def hasStablePresentationCompiler(scalaVersion: String): Boolean =
      SemVer.isCompatibleVersion(
        firstScala3PCVersion,
        scalaVersion,
      )

    def isSupportedInOlderVersion(version: String): Boolean =
      removedScalaVersions.contains(version)

    def resolve(
        scalaVersion: String
    )(implicit ec: ExecutionContext): Option[MtagsBinaries] = {
      if (hasStablePresentationCompiler(scalaVersion))
        resolve(
          scalaVersion.stripSuffix("-nonbootstrapped"),
          original = None,
          resolveType = ResolveType.StablePC,
        )
      else
        resolve(
          scalaVersion,
          original = None,
          resolveType = ResolveType.Regular,
        )
    }

    private object ResolveType extends Enumeration {
      val Regular, StablePC = Value
    }

    /**
     * Resolving order is following:
     * 1. Built-in mtags matching scala version
     * 2. If presentation compiler is available we resolve it otherwise we resolve mtags.
     */
    private def resolve(
        scalaVersion: String,
        original: Option[String],
        resolveType: ResolveType.Value,
    )(implicit ec: ExecutionContext): Option[MtagsBinaries] = {

      def fetch(fetchResolveType: ResolveType.Value, tries: Int = 5): State =
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
          val jarsFuture = Future {
            fetchResolveType match {
              case ResolveType.StablePC =>
                Embedded.downloadScala3PresentationCompiler(scalaVersion)
              case _ => Embedded.downloadMtags(scalaVersion, metalsVersion)
            }
          }

          val jars = Await.result(jarsFuture, 60.seconds)

          State.Success(
            MtagsBinaries.Artifacts(
              scalaVersion,
              jars,
              fetchResolveType == ResolveType.StablePC,
            )
          )
        } catch {
          case NonFatal(_) if tries > 0 =>
            fetch(fetchResolveType, tries - 1)
          case NonFatal(e) =>
            State.Failure(System.currentTimeMillis(), e)
        }

      def shouldResolveAgain(failure: State.Failure): Boolean = {
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
            }
            scribe.debug(msg)
          case fail: State.Failure =>
            val errorMsg = resolveType match {
              case ResolveType.Regular =>
                s"Failed to resolve mtags for $scalaVersion"
              case ResolveType.StablePC =>
                s"Failed to resolve Scala 3 presentation compiler for $scalaVersion"
            }
            scribe.error(errorMsg, fail.exception)
          case _ =>
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
              case null => logResolution(fetch(resolveType))
              case succ: State.Success => succ
              case failure: State.Failure if shouldResolveAgain(failure) =>
                logResolution(fetch(resolveType))
              case failure: State.Failure =>
                failure
            }
          },
        )

        computed match {
          case State.Success(v) => Some(v)
          case _: State.Failure => None
        }
      }
    }

    sealed trait State
    object State {
      case class Success(v: MtagsBinaries.Artifacts) extends State
      case class Failure(
          lastTryMillis: Long,
          exception: Throwable,
      ) extends State
    }
  }

}
