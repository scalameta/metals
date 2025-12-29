package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.bsp.BspStatusState
import scala.meta.internal.bsp.Disconnected
import scala.meta.internal.bsp.NoResponse
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsSyncStatusParams

import ch.epfl.scala.bsp4j.BuildTargetIdentifier

class SyncStatusReporter(
    languageClient: MetalsLanguageClient,
    buildTargets: BuildTargets,
) {
  private val importing = new AtomicReference[Option[Option[String]]](None)
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
      s"SyncStatusReporter: didSync: $uri: ${MetalsSyncStatusParams.Synced}"
    )
    importing.set(Some(Some(uri)))
    languageClient.metalsSyncStatus(
      MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Syncing)
    )
  }

  def expanded(
      uri: String,
      targets: Option[Seq[BuildTargetIdentifier]],
  ): Unit = {
    targets match {
      case Some(targets) =>
        // Successfully expanded the build targets, check if we already know
        targets.find(buildTargets.info(_).isDefined) match {
          case Some(target) =>
            scribe.trace(
              s"SyncStatusReporter: didSync: $uri: targets: ${targets.mkString(", ")}, target: ${target.getUri()}, ${MetalsSyncStatusParams.Synced}"
            )
            // If we already know about at least one target, we can report that the build is synced
            languageClient.metalsSyncStatus(
              MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Synced)
            )
          case None =>
            scribe.trace(
              s"SyncStatusReporter: didSync: $uri: targets: ${targets.mkString(", ")}, ${MetalsSyncStatusParams.Syncing}"
            )
            // Otherwise, we expect the build to be imported soon so we report that it's syncing
            languageClient.metalsSyncStatus(
              MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Syncing)
            )
        }
      case None =>
        scribe.trace(
          s"SyncStatusReporter: didSync: $uri: targets: None, ${MetalsSyncStatusParams.Unknown}"
        )
        // Failed to expand the build targets, report unknown status
        languageClient.metalsSyncStatus(
          MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Unknown)
        )
    }
  }

  def didFocus(uri: String): Unit = {
    currentFocus.set(Some(uri))
    val path = uri.toAbsolutePath
    if (!isBspActive.get()) {
      scribe.trace(
        s"SyncStatusReporter: didFocus: $uri: bsp is not responsive, hiding status"
      )
      languageClient.metalsSyncStatus(
        MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Hidden)
      )
    } else if (path.isScalaOrJava) {
      (buildTargets.inverseSources(path), importing.get()) match {
        case (_, Some(Some(requested))) if requested == uri =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $requested, importing focused: ${MetalsSyncStatusParams.Syncing}"
          )
          // If we're importing the build for this uri, we report that it's syncing
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Syncing)
          )
        case (_, Some(value)) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $value, importing: ${MetalsSyncStatusParams.Syncing}"
          )
          // If we're importing a different build, we report that it's busy
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Busy)
          )
        case (Some(target), None) if buildTargets.info(target).isDefined =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: known target: ${target}, ${MetalsSyncStatusParams.Synced}"
          )
          // If we know about the target, we can report that the build is synced
          languageClient.metalsSyncStatus(
            MetalsSyncStatusParams(uri, MetalsSyncStatusParams.Synced)
          )
        case (_, None) =>
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
    importing.compareAndSet(None, Some(uri))
  }

  def importFinished(uri: Option[String]): Unit = {
    scribe.trace(s"SyncStatusReporter: importFinished: $uri")
    importing.set(None)
    uri.foreach(didFocus)
  }

}
