package tests

import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.collection.immutable.Queue
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.duration.Duration

import scala.meta.internal.metals.BspStatus
import scala.meta.internal.metals.ConnectionBspStatus
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.RequestMonitor
import scala.meta.internal.metals.ServerLivenessMonitor
import scala.meta.internal.metals.clients.language.MetalsStatusParams
import scala.meta.internal.metals.clients.language.NoopLanguageClient
import scala.meta.io.AbsolutePath

class ServerLivenessMonitorSuite extends BaseSuite {
  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  test("basic") {
    val pingInterval = Duration("3s")
    val server = new ResponsiveServer(pingInterval)
    val client = new CountMessageRequestsClient
    val bspStatus = new BspStatus(client, isBspStatusProvider = true)
    val connectionBspStatus = new ConnectionBspStatus(
      bspStatus,
      AbsolutePath(Paths.get(".")),
      Icons.default,
    )
    val livenessMonitor = new ServerLivenessMonitor(
      server,
      () => server.sendRequest(true),
      metalsIdleInterval = pingInterval * 4,
      pingInterval,
      connectionBspStatus,
      "responsive-server",
    )
    connectionBspStatus.connected("responsive-server")
    Thread.sleep(pingInterval.toMillis * 3 / 2)
    assert(livenessMonitor.metalsIsIdle)
    server.sendRequest(false)
    Thread.sleep(pingInterval.toMillis * 2)
    assert(!livenessMonitor.metalsIsIdle)
    Thread.sleep(pingInterval.toMillis * 5)
    assert(livenessMonitor.metalsIsIdle)
    server.sendRequest(false)
    Thread.sleep(pingInterval.toMillis)
    server.sendRequest(false)
    server.sendRequest(false)
    Thread.sleep(pingInterval.toMillis * 2)
    server.sendRequest(false)
    assert(!livenessMonitor.metalsIsIdle)
    assert(livenessMonitor.lastPingOk)
    assert(client.showMessageRequests == 0)
    livenessMonitor.shutdown()
  }
}

/**
 * A mock implementation of a responsive build server,
 * that keeps timestamps of the last incoming and last outgoing, non-ping requests.
 * For every `sendRequest` a response is scheduled to be recorded after `pingInterval`.
 */
class ResponsiveServer(pingInterval: Duration) extends RequestMonitor {
  private val respondAfter = pingInterval.toMillis
  @volatile private var lastOutgoing_ : Option[Long] = None
  private val nextIncoming: AtomicReference[Queue[Long]] = new AtomicReference(
    Queue()
  )

  def sendRequest(isPing: Boolean): Queue[Long] = {
    if (!isPing) lastOutgoing_ = Some(now)
    nextIncoming.getAndUpdate(_.appended(now + respondAfter))
  }

  private def now = System.currentTimeMillis()

  override def lastOutgoing: Option[Long] = lastOutgoing_

  override def lastIncoming: Option[Long] = {
    val now_ = now
    nextIncoming.updateAndGet { queue =>
      queue.findLast(_ <= now_) match {
        case None => queue
        case Some(last) => queue.dropWhile(_ != last)
      }
    }.headOption
  }
}

class CountMessageRequestsClient extends NoopLanguageClient {
  var showMessageRequests = 0

  override def metalsStatus(params: MetalsStatusParams): Unit =
    if (
      params == ConnectionBspStatus.noResponseParams(
        "responsive-server",
        Icons.default,
      )
    ) {
      showMessageRequests += 1
    }
}
