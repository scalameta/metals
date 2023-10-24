package scala.meta.internal.bsp

import java.util.concurrent.atomic.AtomicBoolean

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType
import scala.meta.io.AbsolutePath

class ConnectionBspStatus(
    bspStatus: BspStatus,
    folderPath: AbsolutePath,
    icons: Icons,
) {
  private val isServerResponsive = new AtomicBoolean(false)
  val status: MetalsStatusParams => Unit = bspStatus.status(folderPath, _)

  def connected(serverName: String): Unit =
    if (isServerResponsive.compareAndSet(false, true))
      status(ConnectionBspStatus.connectedParams(serverName, icons))
  def noResponse(serverName: String): Unit =
    if (isServerResponsive.compareAndSet(true, false)) {
      scribe.debug("server liveness monitor detected no response")
      status(ConnectionBspStatus.noResponseParams(serverName, icons))
    }

  def disconnected(): Unit = {
    isServerResponsive.set(false)
    status(ConnectionBspStatus.disconnectedParams)
  }

  def isBuildServerResponsive: Boolean = isServerResponsive.get()
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
}
