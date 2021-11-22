package scala.meta.internal.metals

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration._
import scala.util.control.NonFatal

import coursierapi.error.SimpleResolutionError

trait MtagsResolver {
  def resolve(scalaVersion: String): Option[MtagsBinaries]
  final def isSupportedScalaVersion(version: String): Boolean =
    resolve(version).isDefined
}

object MtagsResolver {

  def default(): MtagsResolver = new Default

  class Default extends MtagsResolver {

    private val states =
      new ConcurrentHashMap[String, State]()

    def resolve(scalaVersion: String): Option[MtagsBinaries] = {
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

      def fetch(tries: Int = 0): State = {
        try {
          val jars = Embedded.downloadMtags(scalaVersion)
          State.Success(MtagsBinaries.Artifacts(scalaVersion, jars))
        } catch {
          case NonFatal(e) =>
            logError(e)
            State.Failure(System.currentTimeMillis(), tries)
        }
      }
      def shouldResolveAgain(failure: State.Failure): Boolean = {
        failure.tries < State.maxTriesInARow ||
        (System
          .currentTimeMillis() - failure.lastTryMillis) > 5.minutes.toMillis
      }

      // The metals_2.12 artifact depends on mtags_2.12.x where "x" matches
      // `mtags.BuildInfo.scalaCompilerVersion`. In the case when
      // `info.getScalaVersion == mtags.BuildInfo.scalaCompilerVersion` then we
      // skip fetching the mtags module from Maven.
      if (MtagsBinaries.isBuildIn(scalaVersion)) {
        Some(MtagsBinaries.BuildIn)
      } else {
        val computed = states.compute(
          scalaVersion,
          (_, value) => {
            value match {
              case null => fetch()
              case succ: State.Success => succ
              case failure: State.Failure =>
                if (shouldResolveAgain(failure))
                  fetch(failure.tries + 1)
                else {
                  scribe.info(s"No mtags for ${scalaVersion}.")
                  failure
                }
            }
          }
        )
        computed match {
          case State.Success(v) => Some(v)
          case _: State.Failure => None
        }
      }
    }

    sealed trait State
    object State {
      val maxTriesInARow: Int = 2
      case class Success(v: MtagsBinaries.Artifacts) extends State
      case class Failure(lastTryMillis: Long, tries: Int) extends State
    }
  }

}
