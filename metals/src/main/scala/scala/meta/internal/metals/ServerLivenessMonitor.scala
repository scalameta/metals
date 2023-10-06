package scala.meta.internal.metals

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.Duration

import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.StatusType

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

trait RequestMonitor {
  def lastOutgoing: Option[Long]
  def lastIncoming: Option[Long]
}

class RequestMonitorImpl(bspStatus: BspStatus) extends RequestMonitor {
  @volatile private var lastOutgoing_ : Option[Long] = None
  @volatile private var lastIncoming_ : Option[Long] = None

  val wrapper: MessageConsumer => MessageConsumer = consumer =>
    new MessageConsumer {
      def consume(message: Message): Unit = {
        message match {
          // we don't count the `buildTargets` request, since it's the one used for pinging
          case m: RequestMessage if m.getMethod() != "workspace/buildTargets" =>
            outgoingMessage()
          case _: ResponseMessage => incomingMessage()
          case _: NotificationMessage => incomingMessage()
          case _ =>
        }
        consumer.consume(message)
      }

    }

  private def outgoingMessage() = lastOutgoing_ = now
  private def incomingMessage(): Unit = {
    bspStatus.connected()
    lastIncoming_ = now
  }
  private def now = Some(System.currentTimeMillis())

  def lastOutgoing: Option[Long] = lastOutgoing_
  def lastIncoming: Option[Long] = lastIncoming_
}

class ServerLivenessMonitor(
    requestMonitor: RequestMonitor,
    ping: () => Unit,
    metalsIdleInterval: Duration,
    pingInterval: Duration,
    bspStatus: BspStatus,
) {
  private val state: AtomicReference[ServerLivenessMonitor.State] =
    new AtomicReference(ServerLivenessMonitor.Idle)
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  scribe.debug("starting server liveness monitor")

  def runnable(): Runnable = new Runnable {
    def run(): Unit = {
      def now = System.currentTimeMillis()
      def lastIncoming =
        requestMonitor.lastIncoming
          .map(now - _)
          .getOrElse(pingInterval.toMillis)
      def notResponding = lastIncoming > (pingInterval.toMillis * 2)
      def metalsIsIdle =
        requestMonitor.lastOutgoing
          .map(lastOutgoing =>
            (now - lastOutgoing) > metalsIdleInterval.toMillis
          )
          .getOrElse(true)
      if (!metalsIsIdle) {
        val currState = state.getAndUpdate {
          case ServerLivenessMonitor.Idle => ServerLivenessMonitor.FirstPing
          case _ => ServerLivenessMonitor.Running
        }
        currState match {
          case ServerLivenessMonitor.Idle =>
            scribe.debug("setting server liveness monitor state to FirstPing")
          case ServerLivenessMonitor.FirstPing =>
            scribe.debug("setting server liveness monitor state to Running")
          case _ =>
        }
        if (currState == ServerLivenessMonitor.Running) {
          if (notResponding) {
            bspStatus.noResponse()
          }
        }
        scribe.debug("server liveness monitor: pinging build server...")
        ping()
      } else {
        if (state.get() != ServerLivenessMonitor.Idle)
          scribe.debug("setting server liveness monitor state to Idle")
        state.set(ServerLivenessMonitor.Idle)
      }
    }
  }
  val scheduled: ScheduledFuture[_ <: Object] = scheduler.scheduleAtFixedRate(
    runnable(),
    pingInterval.toMillis,
    pingInterval.toMillis,
    TimeUnit.MILLISECONDS,
  )

  def isBuildServerResponsive: Boolean = bspStatus.isBuildServerResponsive

  def shutdown(): Unit = {
    scribe.debug("shutting down server liveness monitor")
    scheduled.cancel(true)
    scheduler.shutdown()
    bspStatus.disconnected()
  }

  def getState: ServerLivenessMonitor.State = state.get()
}

object ServerLivenessMonitor {

  /**
   * State of the metals server:
   *  - Idle - set as initial state and after metals goes into idle state
   *  - FistPing - set after first ping after metals comes out of idle state
   *  - Running - set after 2nd, 3rd... nth pings after metals comes out of idle state
   */
  sealed trait State
  object Idle extends State
  object FirstPing extends State
  object Running extends State

}

class BspStatus(
    client: MetalsLanguageClient,
    serverName: String,
    icons: Icons,
) {
  private val isServerResponsive = new AtomicBoolean(false)

  def connected(): Unit =
    if (isServerResponsive.compareAndSet(false, true))
      client.metalsStatus(BspStatus.connectedParams(serverName, icons))
  def noResponse(): Unit =
    if (isServerResponsive.compareAndSet(true, false)) {
      scribe.debug("server liveness monitor detected no response")
      client.metalsStatus(BspStatus.noResponseParams(serverName, icons))
    }
  def disconnected(): Unit = client.metalsStatus(BspStatus.disconnectedParams)

  def isBuildServerResponsive: Boolean = isServerResponsive.get()
}

object BspStatus {
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
