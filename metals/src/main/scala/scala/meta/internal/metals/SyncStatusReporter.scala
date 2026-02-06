package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.bsp.BspStatusState
import scala.meta.internal.bsp.Disconnected
import scala.meta.internal.bsp.NoResponse
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsSyncStatusParams

object SyncStatusReporter {
  private sealed trait Status
  private case class Importing(uri: Option[String]) extends Status
  private case object Indexing extends Status
  private case object Idle extends Status
}

class SyncStatusReporter(
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
) {
  import SyncStatusReporter._
  private val status = new AtomicReference[Status](Idle)
  private val isBspActive = new AtomicReference[Boolean](false)
  private val currentFocus = new AtomicReference[Option[String]](None)

  def statusChange(state: BspStatusState): Unit = {
    scribe.trace(
      s"SyncStatusReporter: statusChange: ${state.currentState}"
    )
    val newState = state.currentState match {
      case NoResponse | Disconnected => false
      case _ => true
    }
    if (isBspActive.getAndSet(newState) != newState) {
      currentFocus.get().foreach(didFocus)
    }
  }

  def onSync(uri: String): Unit = {
    scribe.trace(
      s"SyncStatusReporter: onSync: $uri: ${MetalsSyncStatusParams.Synced}"
    )
    status.set(Importing(Some(uri)))
    didFocus(uri)
  }

  def didFocus(uri: String): Unit = {
    currentFocus.set(Some(uri))
    val path = uri.toAbsolutePath
    if (!isBspActive.get()) {
      scribe.trace(
        s"SyncStatusReporter: didFocus: $uri: bsp is not responsive, hiding status"
      )
      status.set(Idle)
      languageClient.metalsSyncStatus(
        MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Hidden)
      )
    } else if (path.isScalaOrJava) {
      (buildTargets.inverseSources(path), status.get()) match {
        case (_, Importing(Some(requested))) if requested == uri =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $requested, importing focused: ${MetalsSyncStatusParams.Syncing}"
          )
          // If we're importing the build for this uri, we report that it's syncing
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Syncing)
          )
        case (_, Importing(value)) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $value, importing: ${MetalsSyncStatusParams.Syncing}"
          )
          // If we're importing a different build, we report that it's busy
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Busy)
          )
        case (_, Indexing) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: indexing: ${MetalsSyncStatusParams.Syncing}"
          )
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Indexing)
          )
        case (Some(target), Idle) if buildTargets.info(target).isDefined =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: known target: ${target}, ${MetalsSyncStatusParams.Synced}"
          )
          // If we know about the target, we can report that the build is synced
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Synced)
          )
        case (_, Idle) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: unknown target: ${MetalsSyncStatusParams.Untracked}"
          )
          // If we don't know about the target, we report that it's untracked and allow the user to click
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Untracked)
          )
      }
    } else {
      scribe.trace(
        s"SyncStatusReporter: didFocus: $uri: not a scala or java file, hiding status"
      )
      languageClient.metalsSyncStatus(
        MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Hidden)
      )
    }
  }

  def importStarted(uri: Option[String]): Unit = {
    scribe.trace(s"SyncStatusReporter: importStarted: $uri")
    status.compareAndSet(Idle, Importing(uri))
    uri.orElse(currentFocus.get()).foreach(didFocus)
  }

  def indexingStarted(uri: Option[String]): Unit = {
    scribe.trace(s"SyncStatusReporter: indexingStarted: $uri")
    status.set(Indexing)
    uri.orElse(currentFocus.get()).foreach(didFocus)
  }

  def importFinished(uri: Option[String]): Unit = {
    scribe.trace(s"SyncStatusReporter: importFinished: $uri")
    status.set(Idle)
    uri.orElse(currentFocus.get()).foreach(didFocus)
  }
}
