package scala.meta.internal.metals

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.services.LanguageClient

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
    languageClient: LanguageClient,
    serverName: String,
    metalsIdleInterval: Duration,
    pingInterval: Duration,
)(implicit ex: ExecutionContext) {
  private val state: AtomicReference[ServerLivenessMonitor.State] =
    new AtomicReference(ServerLivenessMonitor.Idle)
  @volatile private var isDismissed = false
  @volatile private var isServerResponsive = true
  @volatile private var reconnectOptions
      : ServerLivenessMonitor.ReconnectOptions =
    ServerLivenessMonitor.ReconnectOptions.empty
  def setReconnect(reconnect: () => Future[Unit]): Unit = {
    reconnectOptions = ServerLivenessMonitor.ReconnectOptions(reconnect)
  }
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
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
        if (currState == ServerLivenessMonitor.Running) {
          if (notResponding) {
            isServerResponsive = false
            if (!isDismissed) {
              languageClient
                .showMessageRequest(
                  ServerLivenessMonitor.ServerNotResponding
                    .params(
                      pingInterval,
                      serverName,
                      includeReconnectOption = reconnectOptions.canReconnect,
                    )
                )
                .asScala
                .map {
                  case ServerLivenessMonitor.ServerNotResponding.dismiss =>
                    isDismissed = true
                  case ServerLivenessMonitor.ServerNotResponding.reestablishConnection =>
                    reconnectOptions.reconnect()
                  case _ =>
                }
            }
          } else {
            isServerResponsive = true
          }
        }
        ping()
      } else {
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
    scheduled.cancel(true)
    scheduler.shutdown()
  }

  def getState: ServerLivenessMonitor.State = state.get()
}

object ServerLivenessMonitor {
  object ServerNotResponding {
    def message(pingInterval: Duration, serverName: String): String =
      s"The build server has not responded in over $pingInterval. You may want to restart $serverName build server."

    def params(
        pingInterval: Duration,
        serverName: String,
        includeReconnectOption: Boolean,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(message(pingInterval, serverName))
      val actions =
        if (includeReconnectOption) List(dismiss, reestablishConnection)
        else List(dismiss)
      params.setActions(actions.asJava)
      params.setType(MessageType.Warning)
      params
    }
    val dismiss = new MessageActionItem("Dismiss")
    val reestablishConnection = new MessageActionItem(
      "Reestablish connection with BSP server"
    )
  }

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

  class ReconnectOptions(optReconnect: Option[() => Future[Unit]]) {
    val canReconnect = optReconnect.nonEmpty
    val reconnect: () => Future[Unit] =
      optReconnect.getOrElse(() => Future.successful(()))
  }

  object ReconnectOptions {
    def empty = new ReconnectOptions(None)
    def apply(reconnect: () => Future[Unit]) = new ReconnectOptions(
      Some(reconnect)
    )
  }

}
