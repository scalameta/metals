package scala.meta.internal.bsp

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.io.AbsolutePath

class ConnectionBspStatus(
    bspStatus: BspStatus,
    folderPath: AbsolutePath,
    icons: Icons,
)(implicit rc: ReportContext) {
  private val status = new AtomicReference[BspStatusState](
    BspStatusState(Disconnected, None, None)
  )
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
    val currentState = status.get()
    currentState.currentState match {
      case error @ ErrorMessage(_) => showState(error, currentState)
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
    val old = status.getAndUpdate(_.changeState(newState)._2)
    val (newServerState, newBspState) = old.changeState(newState)
    newServerState.foreach(showState(_, newBspState, errorReports))
  }

  private def showState(
      state: BspServerState,
      currState: BspStatusState,
      errorReports: Set[String] = currentSessionErrors.get(),
  ) = {
    def serverName = currState.serverName.getOrElse("bsp")
    val showParams =
      state match {
        case Disconnected => ConnectionBspStatus.disconnectedParams
        case NoResponse =>
          ConnectionBspStatus.noResponseParams(serverName, icons)
        case Connected(serverName) =>
          ConnectionBspStatus.connectedParams(serverName, icons)
        case ErrorMessage(message) =>
          val currentSessionReports = errorReports.intersect(
            rc.bloop.getReports().map(_.toPath.toUri().toString()).toSet
          )
          if (currentSessionReports.isEmpty)
            ConnectionBspStatus.connectedParams(serverName, icons)
          else
            ConnectionBspStatus.bspErrorParams(
              serverName,
              icons,
              message,
              currentSessionReports.size,
            )
      }
    bspStatus.status(folderPath, showParams)
  }
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
      command = ServerCommands.ConnectBuildServer.id,
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
      command = ServerCommands.RunDoctor.id,
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
    previousState: Option[BspServerState],
    serverName: Option[String],
) {
  def changeState(
      newState: BspServerState
  ): (Option[BspServerState], BspStatusState) = {
    newState match {
      case Disconnected if currentState != Disconnected => moveTo(Disconnected)
      case NoResponse if currentState != NoResponse => moveTo(NoResponse)
      case ErrorMessage(msg) =>
        currentState match {
          case NoResponse =>
            (
              None,
              BspStatusState(NoResponse, Some(ErrorMessage(msg)), serverName),
            )
          case _ => moveTo(ErrorMessage(msg))
        }
      case Connected(serverName) =>
        currentState match {
          case Disconnected => moveTo(Connected(serverName))
          case NoResponse =>
            previousState match {
              case Some(ErrorMessage(msg)) => moveTo(ErrorMessage(msg))
              case _ => moveTo(Connected(serverName))
            }
          case _ => (None, this)
        }
      case _ => (None, this)
    }
  }

  def moveTo(
      newState: BspServerState
  ): (Some[BspServerState], BspStatusState) = {
    val newServerName =
      newState match {
        case Connected(serverName) => Some(serverName)
        case _ => serverName
      }
    (
      Some(newState),
      BspStatusState(newState, Some(currentState), newServerName),
    )
  }
}
