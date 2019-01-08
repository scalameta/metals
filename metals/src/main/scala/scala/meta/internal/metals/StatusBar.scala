package scala.meta.internal.metals

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsEnrichments._

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
    client: () => MetalsLanguageClient,
    time: Time,
    progressTicks: ProgressTicks = ProgressTicks.braille
) extends Cancelable {

  def trackFuture[T](
      message: String,
      value: Future[T],
      showTimer: Boolean = false
  ): Future[T] = {
    items.add(Progress(message, value, showTimer))
    tickIfHidden()
    value
  }

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
      unit: TimeUnit
  ): Unit = {
    cancel()
    scheduledFuture =
      sh.scheduleAtFixedRate(() => tick(), initialDelay, period, unit)
  }

  def tick(): Unit = {
    garbageCollect()
    mostRelevant() match {
      case Some(value) =>
        val isUnchanged = activeItem.exists {
          // Don't re-publish static messages.
          case m: Message => m eq value
          case _ => false
        }
        if (!isUnchanged) {
          activeItem = Some(value)
          val show: java.lang.Boolean = if (isHidden) true else null
          val params = value match {
            case Message(p) =>
              p.copy(show = show)
            case _ =>
              MetalsStatusParams(value.formattedMessage, show = show)
          }
          value.show()
          client().metalsStatus(params)
          isHidden = false
        }
      case None =>
        if (!isHidden && !isActiveMessage) {
          client().metalsStatus(MetalsStatusParams("", hide = true))
          isHidden = true
          activeItem = None
        }
    }
  }

  private var activeItem: Option[Item] = None
  private def isActiveMessage: Boolean = activeItem.exists {
    case m: Message => !m.isOutdated
    case _ => false
  }
  sealed abstract class Item {
    val timer = new Timer(time)
    private var firstShow: Option[Timer] = None
    def show(): Unit = {
      if (firstShow.isEmpty) {
        firstShow = Some(new Timer(time))
      }
    }
    def priority: Long = timer.elapsedNanos
    def isRecent: Boolean = timer.elapsedSeconds < 3
    private val dots = new AtomicInteger()
    def formattedMessage: String = this match {
      case Message(value) => value.text
      case Progress(message, _, showTimer) =>
        if (showTimer) {
          val seconds = timer.elapsedSeconds
          if (seconds == 0) s"$message   "
          else s"$message ${Timer.readableSeconds(seconds)}"
        } else {
          message + progressTicks.format(dots.getAndIncrement())
        }
    }
    def isOutdated: Boolean =
      timer.elapsedSeconds > 10 ||
        firstShow.exists(_.elapsedSeconds > 5)
    def isStale: Boolean = this match {
      case Message(_) => (firstShow.isDefined && !isRecent) || isOutdated
      case Progress(_, job, _) => job.isCompleted
    }
  }
  private case class Message(params: MetalsStatusParams) extends Item
  private case class Progress(
      message: String,
      job: Future[_],
      showTimer: Boolean
  ) extends Item

  private var isHidden: Boolean = true
  private def tickIfHidden(): Unit = {
    if (isHidden) tick()
  }

  private val items = new ConcurrentLinkedQueue[Item]()
  def pendingItems: Iterable[String] = items.asScala.map(_.formattedMessage)
  private def garbageCollect(): Unit = {
    items.removeIf(_.isStale)
  }
  private def mostRelevant(): Option[Item] = {
    if (items.isEmpty) None
    else {
      Some(items.asScala.maxBy { item =>
        (item.isRecent, item.priority)
      })
    }
  }

  override def cancel(): Unit = {
    if (scheduledFuture != null) {
      scheduledFuture.cancel(true)
    }
  }
}
