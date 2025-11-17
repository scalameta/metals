package scala.meta.internal.metals

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import scala.meta.infra
import scala.meta.infra.MonitoringClient
import scala.meta.internal.infra.NoopMonitoringClient
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.WorkDoneProgress.Token
import scala.meta.pc.ProgressBars

import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.jsonrpc.messages
import org.eclipse.lsp4j.services.LanguageClient

abstract class BaseWorkDoneProgress extends ProgressBars {
  def trackBlocking[T](message: String)(thunk: => T): T
  def startProgress(
      message: String,
      withProgress: Boolean = false,
      showTimer: Boolean = true,
      onCancel: Option[() => Unit] = None,
      timeout: Option[ProgressTimeout] = None,
  ): (Task, Future[Token])

  def endProgress(token: Future[Token]): Future[Unit]
}

case class ProgressTimeout(timeout: FiniteDuration, onTimeout: () => Unit) {
  def isTimedOut(timer: Timer): Boolean = {
    timer.elapsedMillis > timeout.toMillis
  }
}

object EmptyProgressBar extends ProgressBars.ProgressBar {
  override def title(): String = ""
  override def message(): String = ""
  override def progress(): Long = 0
  override def total(): Long = 0
  override def updateProgress(progress: Long, total: Long): Unit = ()
  override def updateMessage(message: String): Unit = ()
}
object EmptyWorkDoneProgress extends BaseWorkDoneProgress {
  override def start(
      params: ProgressBars.StartProgressBarParams
  ): ProgressBars.ProgressBar = EmptyProgressBar
  override def end(progressBar: ProgressBars.ProgressBar): Unit = ()
  override def trackBlocking[T](message: String)(thunk: => T): T = thunk
  override def endProgress(token: Future[Token]): Future[Unit] =
    Future.successful(())
  def startProgress(
      message: String,
      withProgress: Boolean,
      showTimer: Boolean,
      onCancel: Option[() => Unit],
      timeout: Option[ProgressTimeout],
  ): (Task, Future[Token]) = (
    Task.empty(Time.system),
    Future.successful(messages.Either.forLeft[String, Integer]("")),
  )
}
case class Task(
    onCancel: Option[() => Unit],
    showTimer: Boolean,
    maybeProgress: Option[TaskProgress],
    time: Time,
    timeout: Option[ProgressTimeout],
) {
  val timer = new Timer(time)
  val wasFinished = new AtomicBoolean(false)
  def timedOut(): Option[ProgressTimeout] = {
    timeout.filter(t => timer.elapsedMillis > t.timeout.toMillis)
  }
  def additionalMessage: Option[String] =
    if (showTimer) {
      val seconds = timer.elapsedSeconds
      if (seconds == 0) None
      else {
        val prefix = maybeProgress.fold("")(p =>
          if (p.message.nonEmpty) s"${p.message} " else ""
        )
        maybeProgress match {
          case Some(TaskProgress(percentage))
              if percentage > 0 && seconds > 3 =>
            Some(s"$prefix${Timer.readableSeconds(seconds)} ($percentage%)")
          case _ =>
            Some(s"$prefix${Timer.readableSeconds(seconds)}")
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
  def empty(time: Time): Task =
    Task(
      onCancel = None,
      showTimer = false,
      maybeProgress = None,
      time = time,
      timeout = None,
    )
}
class WorkDoneProgress(
    client: LanguageClient,
    time: Time,
    metrics: MonitoringClient = NoopMonitoringClient,
)(implicit ec: ExecutionContext)
    extends BaseWorkDoneProgress
    with Cancelable {

  private val taskMap = new ConcurrentHashMap[Token, Task]()

  private var scheduledFuture: ScheduledFuture[_] = _

  private class WorkDoneProgressBar(
      val params: ProgressBars.StartProgressBarParams,
      val task: Task,
      val token: Future[Token],
  ) extends ProgressBars.ProgressBar {
    override def title(): String = params.title
    override def message(): String = task.additionalMessage.getOrElse("")
    override def progress(): Long = task.maybeProgress.fold(0L)(_.progress)
    override def total(): Long = task.maybeProgress.fold(0L)(_.total)
    override def updateProgress(progress: Long, total: Long): Unit = {
      task.maybeProgress match {
        case None =>
          throw new IllegalStateException(
            "can't call updateProgress unless withProgress is true"
          )
        case Some(value) =>
          value.update(progress, total)
      }
    }
    override def updateMessage(message: String): Unit = {
      task.maybeProgress match {
        case None =>
          throw new IllegalStateException(
            "can't call updateMessage unless withProgress is true"
          )
        case Some(value) =>
          value.message = message
      }
    }
  }
  override def start(
      params: ProgressBars.StartProgressBarParams
  ): ProgressBars.ProgressBar = {
    val onCancel = Option(params.onCancel.orElse(null)).map(r => () => r.run())
    val timeout = Option(params.timeout.orElse(null)).map(t =>
      ProgressTimeout(
        FiniteDuration(t.duration.toMillis, TimeUnit.MILLISECONDS),
        () => t.onTimeout.run(),
      )
    )
    val (task, token) = startProgress(
      params.title,
      params.progressEnabled,
      params.timerEnabled,
      onCancel,
      timeout,
    )
    new WorkDoneProgressBar(params, task, token)
  }

  override def end(progressBar: ProgressBars.ProgressBar): Unit = {
    val bar = progressBar.asInstanceOf[WorkDoneProgressBar]
    endTask(bar.task, bar.token)
  }

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

  override def startProgress(
      message: String,
      withProgress: Boolean = false,
      showTimer: Boolean = true,
      onCancel: Option[() => Unit] = None,
      timeout: Option[ProgressTimeout] = None,
  ): (Task, Future[Token]) = {
    val uuid = UUID.randomUUID().toString()
    val token = messages.Either.forLeft[String, Integer](uuid)

    val optProgress = Option.when(withProgress)(TaskProgress.empty)
    val task = Task(onCancel, showTimer, optProgress, time, timeout)
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
      val task = taskMap.getOrDefault(token, Task.empty(time))
      task.maybeProgress match {
        case Some(progress) =>
          progress.update(percentage)
          notifyProgress(token, task)
        case None =>
      }
    }

  def notifyProgress(token: Token): Unit = {
    val task = taskMap.getOrDefault(token, Task.empty(time))
    if (task.showTimer) notifyProgress(token, task)
    else Future.successful(())
  }

  private def endTask(task: Task, token: Future[Token]): Unit = {
    task.wasFinished.set(true)
    endProgress(token)
    taskMap.remove(token)
  }

  private def notifyProgress(token: Token, task: Task): Unit = {
    // make sure we don't update if a task was finished
    if (task.wasFinished.get()) {
      endProgress(Future.successful(token))
      taskMap.remove(token)
    } else {
      task.timedOut() match {
        case Some(timeout) =>
          timeout.onTimeout()
          endTask(task, Future.successful(token))
          return
        case _ =>
      }

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

  override def endProgress(token: Future[Token]): Future[Unit] =
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

  def trackProgressFuture[T](
      message: String,
      value: TaskProgress => Future[T],
      onCancel: Option[() => Unit] = None,
      showTimer: Boolean = true,
      metricName: Option[String] = None,
  )(implicit ec: ExecutionContext): Future[T] = {
    val (task, token) = startProgress(
      message,
      onCancel = onCancel,
      showTimer = showTimer,
      withProgress = true,
    )
    val result = value(task.maybeProgress.get)
    result.onComplete { _ =>
      task.wasFinished.set(true)
      endProgress(token)
      metricName.foreach { name =>
        metrics.recordEvent(infra.Event.duration(name, task.timer.elapsed))
      }
    }
    result
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
