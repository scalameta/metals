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
  private def modes = buildTargets.syncModes.map(mode => mode.id -> mode).toMap
  private val currentMode = new AtomicReference[Option[String]](
    buildTargets.syncModes.headOption.map(_.id)
  )

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

  def onSync(uri: String, mode: String): Unit = {
    scribe.trace(s"SyncStatusReporter: onSync: $uri: $mode")
    if (mode == null || modes.contains(mode)) {
      currentMode.set(Option(mode))
    } else {
      scribe.warn(
        s"SyncStatusReporter: onSync: $uri: $mode: not a valid mode, using ${currentMode.get()}"
      )
    }
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
        SyncStatus(uri, SyncStatus.Hidden)
      )
    } else if (path.isScalaOrJava) {
      (buildTargets.inverseSources(path), status.get()) match {
        case (_, Importing(Some(requested))) if requested == uri =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $requested, importing focused: ${SyncStatus.Syncing}"
          )
          // If we're importing the build for this uri, we report that it's syncing
          languageClient.metalsSyncStatus(
            SyncStatus(uri, SyncStatus.Syncing)
          )
        case (_, Importing(value)) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: requested: $value, importing: ${SyncStatus.Syncing}"
          )
          // If we're importing a different build, we report that it's busy
          languageClient.metalsSyncStatus(
            SyncStatus(uri, SyncStatus.Busy)
          )
        case (_, Indexing) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: indexing: ${SyncStatus.Syncing}"
          )
          languageClient.metalsSyncStatus(
            SyncStatus(uri, SyncStatus.Indexing)
          )
        case (Some(target), Idle) if buildTargets.info(target).isDefined =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: known target: ${target}, ${SyncStatus.Synced}"
          )
          // If we know about the target, we can report that the build is synced
          languageClient.metalsSyncStatus(
            SyncStatus(uri, SyncStatus.Synced)
          )
        case (_, Idle) =>
          scribe.trace(
            s"SyncStatusReporter: didFocus: $uri: unknown target: ${SyncStatus.Untracked}"
          )
          // If we don't know about the target, we report that it's untracked and allow the user to click
          languageClient.metalsSyncStatus(
            SyncStatus(uri, SyncStatus.Untracked)
          )
      }
    } else {
      scribe.trace(
        s"SyncStatusReporter: didFocus: $uri: not a scala or java file, hiding status"
      )
      languageClient.metalsSyncStatus(
        SyncStatus(uri, SyncStatus.Hidden)
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

  sealed trait SyncStatus {
    def name: String
    def kind: String = null
    def text: String = null
    def tooltip: String = null
    def command: String = null
  }
  object SyncStatus {
    private def modesTooltip = if (modes.size <= 1) ""
    else {
      val tooltip = new StringBuilder()
      tooltip.append("\n\n---\n\nAvailable modes:\n")
      for (mode <- modes.values) {
        val current = currentMode
          .get()
          .orElse(buildTargets.syncModes.headOption.map(_.id))
          .exists(_ == mode.id)
        tooltip
          .append(s" - [")
          .append(if (current) "**" else "")
          .append(mode.name)
          .append(if (current) " (current)**" else "")
          .append(
            s"""](command:${ClientCommands.SyncFile.id}?"${mode.id}" "Run ${mode.name} sync")"""
          )
          .append(if (current) "**" else "")
          .append(": ")
          .append(s"${mode.description}")
          .append(if (current) "**" else "")
          .append("\n")
      }
      tooltip.append("\n---\n\n").toString()
    }
    private def syncCommand =
      s"${ClientCommands.SyncFile.id}${currentMode.get().map("?[\"" + _ + "\"]").getOrElse("")}"
    def apply(document: String, status: SyncStatus): MetalsSyncStatusParams =
      new MetalsSyncStatusParams(
        document,
        status.name,
        status.kind,
        status.text,
        status.tooltip,
        status.command,
      )

    case object Synced extends SyncStatus {
      def name = "synced"
      override def text: String = "$(check) Synced"
      override def kind: String = "info"
      override def tooltip: String =
        s"""|Document is synced with build server. Click to sync again.${modesTooltip}
            |""".stripMargin
      override def command: String = syncCommand
    }
    case object Syncing extends SyncStatus {
      def name = "syncing"
      override def text: String = "$(sync~spin) Syncing"
      override def kind = "warning"
      override def tooltip: String =
        "Syncing document with build server. Click to see logs."
      override def command: String = ClientCommands.ToggleLogs.id
    }
    case object Indexing extends SyncStatus {
      def name = "indexing"
      override def text: String = "$(sync~spin) Indexing"
      override def kind: String = "warning"
      override def tooltip: String =
        "Indexing project. Click to see logs."
      override def command: String = ClientCommands.ToggleLogs.id
    }
    case object Busy extends SyncStatus {
      def name = "busy"
      override def text: String = "$(sync~spin) Busy"
      override def kind: String = "warning"
      override def tooltip: String =
        "The build server is busy importing the build. Click to see logs."
      override def command: String = ClientCommands.ToggleLogs.id
    }
    case object Untracked extends SyncStatus {
      def name = "untracked"
      override def text: String = "$(alert) Untracked"
      override def kind: String = "warning"
      override def tooltip: String =
        s"""|Document is not synced with build server. Click to sync.
            |
            |${modesTooltip}
            |""".stripMargin
      override def command: String = syncCommand
    }
    case object Unknown extends SyncStatus {
      def name = "unknown"
      override def text: String = "$(circle-slash) Unknown"
      override def kind: String = "error"
      override def tooltip: String =
        s"""|File is not part of any build target. Click to try again.
            |
            |${modesTooltip}
            |""".stripMargin
      override def command: String = syncCommand
    }
    case object Hidden extends SyncStatus {
      def name = "hidden"
    }
  }

  def apply(document: String, status: SyncStatus): MetalsSyncStatusParams =
    new MetalsSyncStatusParams(
      document,
      status.name,
      status.kind,
      status.text,
      status.tooltip,
      status.command,
    )
}
