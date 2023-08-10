package scala.meta.internal.metals

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext
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

class RequestMonitor {
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
    server: MetalsBuildServer,
    languageClient: LanguageClient,
    serverName: String,
    metalsIdleInterval: Duration,
    pingInterval: Duration,
)(implicit ex: ExecutionContext) {
  private val state: AtomicReference[ServerLivenessMonitor.State] =
    new AtomicReference(ServerLivenessMonitor.Idle)
  @volatile private var isDismissed = false
  @volatile private var isServerResponsive = true
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
  val runnable: Runnable = new Runnable {
    def run(): Unit = {
      def now = System.currentTimeMillis()
      def lastIncoming =
        requestMonitor.lastIncoming
          .map(now - _)
          .getOrElse(pingInterval.toMillis)
      def notResponding = lastIncoming > (pingInterval.toMillis * 2)
      def metalsIsIdle =
        requestMonitor.lastOutgoing.exists(lastOutgoing =>
          (now - lastOutgoing) > metalsIdleInterval.toMillis
        )
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
                    .params(pingInterval, serverName)
                )
                .asScala
                .map {
                  case ServerLivenessMonitor.ServerNotResponding.dismiss =>
                    isDismissed = true
                  case _ =>
                }
            }
          } else {
            isServerResponsive = true
          }
        }
        server.workspaceBuildTargets()
      } else {
        state.set(ServerLivenessMonitor.Idle)
      }
    }
  }

  val scheduled: ScheduledFuture[_ <: Object] =
    scheduler.scheduleAtFixedRate(
      runnable,
      pingInterval.toMillis,
      pingInterval.toMillis,
      TimeUnit.MILLISECONDS,
    )

  def isBuildServerResponsive: Boolean = isServerResponsive

  def shutdown(): Unit = {
    scheduled.cancel(true)
    scheduler.shutdown()
  }
}

object ServerLivenessMonitor {
  object ServerNotResponding {
    def message(pingInterval: Duration, serverName: String): String =
      s"The build server has not responded in over $pingInterval. You may want to restart $serverName build server."

    def params(
        pingInterval: Duration,
        serverName: String,
    ): ShowMessageRequestParams = {
      val params = new ShowMessageRequestParams()
      params.setMessage(message(pingInterval, serverName))
      params.setActions(List(dismiss, ok).asJava)
      params.setType(MessageType.Warning)
      params
    }
    val dismiss = new MessageActionItem("Dismiss")
    val ok = new MessageActionItem("OK")
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

}
