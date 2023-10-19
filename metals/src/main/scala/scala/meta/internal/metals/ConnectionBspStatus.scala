package scala.meta.internal.metals

import java.util.concurrent.atomic.AtomicBoolean

import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType

class ConnectionBspStatus(
    client: MetalsLanguageClient,
    serverName: String,
    icons: Icons,
) {
  private val isServerResponsive = new AtomicBoolean(false)

  def connected(): Unit =
    if (isServerResponsive.compareAndSet(false, true))
      client.metalsStatus(ConnectionBspStatus.connectedParams(serverName, icons))
  def noResponse(): Unit =
    if (isServerResponsive.compareAndSet(true, false)) {
      scribe.debug("server liveness monitor detected no response")
      client.metalsStatus(ConnectionBspStatus.noResponseParams(serverName, icons))
    }
  def disconnected(): Unit = client.metalsStatus(ConnectionBspStatus.disconnectedParams)

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
