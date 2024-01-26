package scala.meta.internal.bsp

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.io.AbsolutePath
import scala.meta.pc.ReportContext

class ConnectionBspStatus(
    bspStatus: BspStatus,
    folderPath: AbsolutePath,
    icons: Icons,
)(implicit rc: ReportContext) {
  private val status = new AtomicReference[BspStatusState](
    BspStatusState(Disconnected, None, None, shouldShow = false)
  )

  /** Paths to all bsp error reports from this session (bsp connection). */
  private val currentSessionErrors = new AtomicReference[Set[String]](Set())

  def connected(serverName: String): Unit = changeState(Connected(serverName))

  def noResponse(): Unit = {
    scribe.debug("server liveness monitor detected no response")
    changeState(NoResponse)
  }

  def disconnected(): Unit = {
    currentSessionErrors.set(Set.empty)
    changeState(Disconnected)
  }

  def showError(message: String, pathToReport: Path): Unit = {
    val updatedSet =
      currentSessionErrors.updateAndGet(_ + pathToReport.toUri().toString())
    changeState(ErrorMessage(message), updatedSet)
  }

  def onReportsUpdate(): Unit = {
    status.get().currentState match {
      case ErrorMessage(_) => showState(status.get())
      case _ =>
    }
  }

  def isBuildServerResponsive: Option[Boolean] =
    status.get().currentState match {
      case NoResponse => Some(false)
      case Disconnected => None
      case _ => Some(true)
    }

  private def changeState(
      newState: BspServerState,
      errorReports: Set[String] = currentSessionErrors.get(),
  ) = {
    val newServerState = status.updateAndGet(_.changeState(newState))
    if (newServerState.shouldShow) {
      showState(newServerState, errorReports)
    }
  }

  private def showState(
      statusState: BspStatusState,
      errorReports: Set[String] = currentSessionErrors.get(),
  ) = {
    val showParams =
      statusState.currentState match {
        case Disconnected => ConnectionBspStatus.disconnectedParams
        case NoResponse =>
          ConnectionBspStatus.noResponseParams(statusState.serverName, icons)
        case Connected(serverName) =>
          ConnectionBspStatus.connectedParams(serverName, icons)
        case ErrorMessage(message) =>
          val currentSessionReports = syncWithReportContext(errorReports)
          if (currentSessionReports.isEmpty)
            ConnectionBspStatus.connectedParams(statusState.serverName, icons)
          else
            ConnectionBspStatus.bspErrorParams(
              statusState.serverName,
              icons,
              message,
              currentSessionReports.size,
            )
      }
    bspStatus.status(folderPath, showParams)
  }

  /**
   * To get the actual number of error reports we take an intersection
   * of this session's error reports with the reports in `.metals/.reports/bloop`,
   * this allows for two things:
   *   1. When user deletes the report from file system the warning will disappear.
   *   2. Error deduplication. When for a perticular error a report already exists, we remove the old report.
   * For reports management details look [[scala.meta.internal.metals.StdReporter]]
   */
  private def syncWithReportContext(errorReports: Set[String]) =
    errorReports.intersect(
      rc.bloop.getReports().map(_.toPath.toUri().toString()).asScala.toSet
    )
}

object ConnectionBspStatus {
  def connectedParams(serverName: String, icons: Icons): MetalsStatusParams =
    MetalsStatusParams(
      s"$serverName ${icons.link}",
      "info",
      show = true,
      tooltip = s"Metals is connected to the build server ($serverName).",
    ).withStatusType(StatusType.bsp)

  val disconnectedParams: MetalsStatusParams =
    MetalsStatusParams("", hide = true).withStatusType(StatusType.bsp)

  def noResponseParams(serverName: String, icons: Icons): MetalsStatusParams =
    MetalsStatusParams(
      s"$serverName ${icons.error}",
      "error",
      show = true,
      tooltip = s"Build sever ($serverName) is not responding.",
      command = ClientCommands.ConnectBuildServer.id,
      commandTooltip = "Reconnect.",
    ).withStatusType(StatusType.bsp)

  def bspErrorParams(
      serverName: String,
      icons: Icons,
      message: String,
      errorsNumber: Int,
  ): MetalsStatusParams =
    MetalsStatusParams(
      s"$serverName $errorsNumber ${icons.alert}",
      "warn",
      show = true,
      tooltip = message.trimTo(TOOLTIP_MAX_LENGTH),
      command = ClientCommands.RunDoctor.id,
      commandTooltip = "Open doctor.",
    ).withStatusType(StatusType.bsp)

  private val TOOLTIP_MAX_LENGTH = 150
}

trait BspServerState
case object Disconnected extends BspServerState
case class Connected(serverName: String) extends BspServerState
case class ErrorMessage(message: String) extends BspServerState
case object NoResponse extends BspServerState

case class BspStatusState(
    currentState: BspServerState,
    lastError: Option[ErrorMessage],
    optServerName: Option[String],
    shouldShow: Boolean,
) {
  val serverName: String = optServerName.getOrElse("bsp")
  def changeState(
      newState: BspServerState
  ): BspStatusState = {
    newState match {
      case Disconnected if currentState != Disconnected => moveTo(Disconnected)
      case NoResponse if currentState != NoResponse => moveTo(NoResponse)
      case ErrorMessage(msg) =>
        currentState match {
          case NoResponse =>
            BspStatusState(
              NoResponse,
              Some(ErrorMessage(msg)),
              optServerName,
              shouldShow = false,
            )
          case _ => moveTo(ErrorMessage(msg))
        }
      case Connected(serverName) =>
        currentState match {
          case Disconnected => moveTo(Connected(serverName))
          case NoResponse =>
            lastError match {
              case Some(error) => moveTo(error)
              case _ => moveTo(Connected(serverName))
            }
          case _ => this.copy(shouldShow = false)
        }
      case _ => this.copy(shouldShow = false)
    }
  }

  def moveTo(
      newState: BspServerState
  ): BspStatusState = {
    val newServerName =
      newState match {
        case Connected(serverName) => Some(serverName)
        case _ => optServerName
      }
    val lastError = Some(currentState).collect { case error: ErrorMessage =>
      error
    }
    BspStatusState(newState, lastError, newServerName, shouldShow = true)
  }
}
