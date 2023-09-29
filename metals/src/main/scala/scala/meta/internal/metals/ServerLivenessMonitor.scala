package scala.meta.internal.metals

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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

class RequestMonitorImpl extends RequestMonitor {
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
  private def incomingMessage(): Unit = lastIncoming_ = now
  private def now = Some(System.currentTimeMillis())

  def lastOutgoing: Option[Long] = lastOutgoing_
  def lastIncoming: Option[Long] = lastIncoming_
}

class ServerLivenessMonitor(
    requestMonitor: RequestMonitor,
    ping: () => Unit,
    client: MetalsLanguageClient,
    metalsIdleInterval: Duration,
    pingInterval: Duration,
    serverName: String,
    icons: Icons,
) {
  private val state: AtomicReference[ServerLivenessMonitor.State] =
    new AtomicReference(ServerLivenessMonitor.Idle)
  @volatile private var isServerResponsive = true
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
  val connectedParams: MetalsStatusParams =
    ServerLivenessMonitor.connectedParams(serverName, icons)
  val noResponseParams: MetalsStatusParams =
    ServerLivenessMonitor.noResponseParams(serverName, icons)

  client.metalsStatus(connectedParams)
  scribe.debug("starting BSLM")

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
            scribe.debug("setting BSLM state to FirstPing")
          case ServerLivenessMonitor.FirstPing =>
            scribe.debug("setting BSLM state to Running")
          case _ =>
        }
        if (currState == ServerLivenessMonitor.Running) {
          if (notResponding && isServerResponsive) {
            scribe.debug("BSLM detected no response")
            client.metalsStatus(noResponseParams)
          } else if (!notResponding && !isServerResponsive)
            client.metalsStatus(connectedParams)
          isServerResponsive = !notResponding
        }
        scribe.debug("BSLM: pinging build server...")
        ping()
      } else {
        if (state.get() != ServerLivenessMonitor.Idle)
          scribe.debug("setting BSLM state to Idle")
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

  def isBuildServerResponsive: Boolean = isServerResponsive

  def shutdown(): Unit = {
    scribe.debug("shutting down BSLM")
    scheduled.cancel(true)
    scheduler.shutdown()
    client.metalsStatus(ServerLivenessMonitor.disconnectedParams)
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
