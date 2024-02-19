package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsStatusParams

/**
 * Manages sending metals/status notifications to the editor client.
 *
 * Some design constraints and design decisions:
 * - we should publish no more than status update per second so the user
 *   has time to read the notification.
 * - there is only one active status bar notification at each time, in case
 *   there are multiple candidates we pick the item with higher priority.
 * - it's acceptable that a low priority notification may never appear in
 *   the status bar.
 * - long-running jobs like compilation or "import workspace" are active
 *   as long as the attached `Future[_]` is not completed.
 */
final class StatusBar(
    client: MetalsLanguageClient,
    time: Time,
) extends Cancelable {

  def addMessage(params: MetalsStatusParams): Unit = {
    items.add(Message(params))
    tickIfHidden()
  }
  def addMessage(message: String): Unit = {
    addMessage(MetalsStatusParams(message))
  }

  private var scheduledFuture: ScheduledFuture[_] = _

  def start(
      sh: ScheduledExecutorService,
      initialDelay: Long,
      period: Long,
      unit: TimeUnit,
  ): Unit = {
    cancel()
    scheduledFuture =
      sh.scheduleAtFixedRate(() => tick(), initialDelay, period, unit)
  }

  def tick(): Unit = {
    try tickUnsafe()
    catch {
      case NonFatal(e) =>
        scribe.error("status bar tick failed", e)
    }
  }
  private def tickUnsafe(): Unit = {
    garbageCollect()
    mostRelevant() match {
      case Some(value) =>
        val isUnchanged = activeItem.exists(_ eq value)
        if (!isUnchanged) {
          activeItem = Some(value)
          val show: java.lang.Boolean = if (isHidden) true else null
          val params = value.params.copy(show = show)
          value.show()
          client.metalsStatus(params)
          isHidden = false
        }
      case None =>
        if (!isHidden && !isActiveMessage) {
          client.metalsStatus(MetalsStatusParams("", hide = true))
          isHidden = true
          activeItem = None
        }
    }
  }

  private var activeItem: Option[Message] = None
  private def isActiveMessage: Boolean = activeItem.exists(!_.isOutdated)
  private case class Message(params: MetalsStatusParams) {
    val timer = new Timer(time)
    private var firstShow: Option[Timer] = None
    def show(): Unit = {
      if (firstShow.isEmpty) {
        firstShow = Some(new Timer(time))
      }
    }
    def priority: Long = timer.elapsedNanos
    def isRecent: Boolean = timer.elapsedSeconds < 3
    def formattedMessage: String = params.text
    def isOutdated: Boolean =
      timer.elapsedSeconds > 10 ||
        firstShow.exists(_.elapsedSeconds > 5)
    def isStale: Boolean = (firstShow.isDefined && !isRecent) || isOutdated
  }

  private var isHidden: Boolean = true
  private def tickIfHidden(): Unit = {
    if (isHidden) tick()
  }

  private val items = new ConcurrentLinkedQueue[Message]()
  def pendingItems: Iterable[String] = items.asScala.map(_.formattedMessage)
  private def garbageCollect(): Unit = {
    items.removeIf(_.isStale)
  }
  private def mostRelevant(): Option[Message] = {
    if (items.isEmpty) None
    else {
      Some(items.asScala.maxBy { item => (item.isRecent, item.priority) })
    }
  }

  override def cancel(): Unit = {
    if (scheduledFuture != null) {
      scheduledFuture.cancel(false)
    }
  }
}
