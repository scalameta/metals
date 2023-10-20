package scala.meta.internal.metals

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration

import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage

trait RequestMonitor {
  def lastOutgoing: Option[Long]
  def lastIncoming: Option[Long]
}

class RequestMonitorImpl(bspStatus: ConnectionBspStatus, serverName: String)
    extends RequestMonitor {
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
    bspStatus.connected(serverName)
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
    bspStatus: ConnectionBspStatus,
    serverName: String,
) {
  @volatile private var lastPing: Long = 0
  val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

  scribe.debug("starting server liveness monitor")

  def runnable(): Runnable = new Runnable {
    def run(): Unit = {
      def lastIncoming =
        requestMonitor.lastIncoming
          .map(now - _)
          .getOrElse(pingInterval.toMillis)
      def notResponding = lastIncoming > (pingInterval.toMillis * 2)
      if (!metalsIsIdle) {
        if (lastPingOk && notResponding) {
          bspStatus.noResponse(serverName)
        }
        scribe.debug("server liveness monitor: pinging build server...")
        lastPing = now
        ping()
      }
    }
  }

  private def now = System.currentTimeMillis()

  def metalsIsIdle: Boolean =
    requestMonitor.lastOutgoing
      .map(lastOutgoing => (now - lastOutgoing) > metalsIdleInterval.toMillis)
      .getOrElse(true)

  def lastPingOk: Boolean = now - lastPing < (pingInterval.toMillis * 2)

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
