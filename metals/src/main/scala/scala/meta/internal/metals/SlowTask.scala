package scala.meta.internal.metals

import java.util.UUID

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._

import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkDoneProgressNotification
import org.eclipse.lsp4j.WorkDoneProgressReport
import org.eclipse.lsp4j.jsonrpc.messages
import org.eclipse.lsp4j.services.LanguageClient

class SlowTask(client: LanguageClient)(implicit ec: ExecutionContext) {
  type Token = messages.Either[String, Integer]
  private def onCancelMap = mutable.Map[Token, () => Unit]()

  def startSlowTask(
      message: String,
      withProgress: Boolean = false,
      onCancel: Option[() => Unit] = None
  ): Future[Token] = {
    val uuid = UUID.randomUUID().toString()
    val token = messages.Either.forLeft[String, Integer](uuid)

    onCancel match {
      case Some(onCancel) => 
        onCancelMap(token) = onCancel
      case None =>
    }

    client
      .createProgress(new WorkDoneProgressCreateParams(token))
      .asScala
      .map { _ =>
        val begin = new WorkDoneProgressBegin()
        begin.setTitle(message)
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
  }

  def notifyProgress(
      token: Future[Token],
      percentage: Int,
      additionalMessage: Option[String] = None,
  ): Future[Unit] = {
    token.map{ token =>
      val report = new WorkDoneProgressReport()
      report.setPercentage(percentage)
      additionalMessage.foreach(msg => report.setMessage(msg))
      val notification =
        messages.Either.forLeft[WorkDoneProgressNotification, Object](
          report
        )
      client.notifyProgress(new ProgressParams(token, notification))
    }
  }

  def endSlowTask(token: Future[Token]): Future[Unit] = {
    token.map{ token =>
      val end = messages.Either.forLeft[WorkDoneProgressNotification, Object](
        new WorkDoneProgressEnd()
      )
      client.notifyProgress(new ProgressParams(token, end))
    }
  }

  //TODO: add possibility to show time
  def trackFuture[T](
      message: String,
      value: Future[T],
      onCancel: Option[() => Unit] = None
  )(implicit ec: ExecutionContext): Future[T] = {
    val token = startSlowTask(message, onCancel = onCancel)
    value.map { result =>
      //TODO:: probably shouldn't do it if it was already cancelled
      endSlowTask(token)
      result
    }
  }

  def trackBlocking[T](message: String)(thunk: => T): T = {
    val token = startSlowTask(message)
    val result = thunk
    endSlowTask(token)
    result
  }

  def canceled(token: Token): Unit = onCancelMap.get(token).foreach(_())
}

object SlowTask {
  type Token = messages.Either[String, Integer]
}
