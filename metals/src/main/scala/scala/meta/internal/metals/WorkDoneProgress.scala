package scala.meta.internal.metals

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.infra
import scala.meta.infra.MonitoringClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.WorkDoneProgress.Token

import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.jsonrpc.messages
import org.eclipse.lsp4j.services.LanguageClient

class WorkDoneProgress(
    client: LanguageClient,
    time: Time,
)(implicit ec: ExecutionContext)
    extends Cancelable {
  case class Task(
      onCancel: Option[() => Unit],
      showTimer: Boolean,
      maybeProgress: Option[TaskProgress],
  ) {
    val timer = new Timer(time)
    val wasFinished = new AtomicBoolean(false)
    def additionalMessage: Option[String] =
      if (showTimer) {
        val seconds = timer.elapsedSeconds
        if (seconds == 0) None
        else {
          maybeProgress match {
            case Some(TaskProgress(percentage)) if seconds > 3 =>
              Some(s"${Timer.readableSeconds(seconds)} ($percentage%)")
            case _ =>
              Some(s"${Timer.readableSeconds(seconds)}")
          }
        }
      } else
        maybeProgress match {
          case Some(TaskProgress(0)) => None
          case Some(TaskProgress(percentage)) => Some(s"($percentage%)")
          case _ => None
        }
  }

  object Task {
    def empty: Task =
      Task(onCancel = None, showTimer = false, maybeProgress = None)
  }

  private val taskMap = new ConcurrentHashMap[Token, Task]()

  private var scheduledFuture: ScheduledFuture[_] = _

  def start(
      sh: ScheduledExecutorService,
      initialDelay: Long,
      period: Long,
      unit: TimeUnit,
  ): Unit = {
    cancel()
    scheduledFuture =
      sh.scheduleAtFixedRate(() => updateTimers(), initialDelay, period, unit)
  }

  private def updateTimers() = taskMap.keys.asScala.foreach(notifyProgress(_))

  def startProgress(
      message: String,
      withProgress: Boolean = false,
      showTimer: Boolean = true,
      onCancel: Option[() => Unit] = None,
  ): (Task, Future[Token]) = {
    val uuid = UUID.randomUUID().toString()
    val token = messages.Either.forLeft[String, Integer](uuid)

    val optProgress = Option.when(withProgress)(TaskProgress.empty)
    val task = Task(onCancel, showTimer, optProgress)
    taskMap.put(token, task)

    val tokenFuture = client
      .createProgress(new WorkDoneProgressCreateParams(token))
      .asScala
      .map { _ =>
        val begin = new WorkDoneProgressBegin()
        begin.setTitle(message)
        task.additionalMessage.foreach(begin.setMessage)
        if (withProgress) {
          begin.setPercentage(0)
        }
        if (onCancel.isDefined) {
          begin.setCancellable(true)
        }
        val notification =
          messages.Either.forLeft[WorkDoneProgressNotification, Object](
            begin
          )
        client.notifyProgress(new ProgressParams(token, notification))
        token
      }
    (task, tokenFuture)
  }

  def notifyProgress(
      token: Future[Token],
      percentage: Int,
  ): Future[Unit] =
    token.map { token =>
      val task = taskMap.getOrDefault(token, Task.empty)
      task.maybeProgress match {
        case Some(progress) =>
          progress.update(percentage)
          notifyProgress(token, task)
        case None =>
      }
    }

  def notifyProgress(token: Token): Unit = {
    val task = taskMap.getOrDefault(token, Task.empty)
    if (task.showTimer) notifyProgress(token, task)
    else Future.successful(())
  }

  private def notifyProgress(token: Token, task: Task): Unit = {
    // make sure we don't update if a task was finished
    if (task.wasFinished.get()) {
      endProgress(Future.successful(token))
      taskMap.remove(token)
    } else {
      val report = new WorkDoneProgressReport()
      task.maybeProgress.foreach { progress =>
        report.setPercentage(progress.percentage)
      }
      task.additionalMessage.foreach(report.setMessage)
      val notification =
        messages.Either.forLeft[WorkDoneProgressNotification, Object](
          report
        )
      client.notifyProgress(new ProgressParams(token, notification))
    }
  }

  def endProgress(token: Future[Token]): Future[Unit] =
    token
      .map { token =>
        taskMap.remove(token)
        val end = new WorkDoneProgressEnd()
        val params =
          messages.Either.forLeft[WorkDoneProgressNotification, Object](end)
        client.notifyProgress(new ProgressParams(token, params))
      }
      .recover {
        case _: NullPointerException =>
        // no such value in the task map, task already ended or cancelled
        case NonFatal(e) =>
          scribe.error("Could not end a progress task", e)
      }

  def trackFuture[T](
      message: String,
      value: Future[T],
      onCancel: Option[() => Unit] = None,
      showTimer: Boolean = true,
      metricName: Option[(String, MonitoringClient)] = None,
  )(implicit ec: ExecutionContext): Future[T] = {
    val (task, token) =
      startProgress(message, onCancel = onCancel, showTimer = showTimer)
    value.onComplete { _ =>
      task.wasFinished.set(true)
      endProgress(token)
      metricName.foreach { case (name, metrics) =>
        metrics.recordEvent(infra.Event.duration(name, task.timer.elapsed))
      }
    }
    value
  }

  def trackBlocking[T](message: String)(thunk: => T): T = {
    val (task, token) = startProgress(message)
    try thunk
    finally {
      task.wasFinished.set(true)
      endProgress(token)
    }
  }

  def canceled(token: Token): Unit =
    try {
      val task = taskMap.remove(token)
      task.onCancel.foreach(_())
    } catch {
      case _: NullPointerException =>
      // no such value in the task map, task already ended or cancelled
    }

  override def cancel(): Unit = {
    if (scheduledFuture != null) {
      scheduledFuture.cancel(false)
    }
  }
}

object WorkDoneProgress {
  type Token = messages.Either[String, Integer]
}
