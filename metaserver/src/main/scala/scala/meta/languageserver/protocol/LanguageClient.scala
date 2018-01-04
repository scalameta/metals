package scala.meta.languageserver.protocol

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.tools.nsc.interpreter.OutputStream
import com.typesafe.scalalogging.LazyLogging
import langserver.core.MessageWriter
import langserver.core.Notifications
import langserver.messages.ApplyWorkspaceEditParams
import langserver.messages.ApplyWorkspaceEditResponse
import langserver.messages.LogMessageParams
import langserver.messages.PublishDiagnostics
import langserver.messages.ShowMessageParams
import langserver.types.CancelParams
import monix.eval.Callback
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.atomic.AtomicInt
import io.circe.Encoder
import io.circe.Decoder
import io.circe.syntax._
import cats.syntax.either._

class LanguageClient(out: OutputStream) extends LazyLogging with Notifications {
  private val writer = new MessageWriter(out)
  private val counter: AtomicInt = Atomic(1)
  private val activeServerRequests =
    TrieMap.empty[RequestId, Callback[Response]]
  def notify[A: Encoder](method: String, notification: A): Unit =
    writer.write(Notification(method, Some(notification.asJson)))
  def serverRespond(response: Response): Unit = response match {
    case Response.Empty => ()
    case x: Response.Success => writer.write(x)
    case x: Response.Error => writer.write(x)
  }
  def clientRespond(response: Response): Unit =
    for {
      id <- response match {
        case Response.Empty => None
        case Response.Success(_, requestId) => Some(requestId)
        case Response.Error(_, requestId) => Some(requestId)
      }
      callback <- activeServerRequests.get(id).orElse {
        logger.error(s"Response to unknown request: $response")
        None
      }
    } {
      activeServerRequests.remove(id)
      callback.onSuccess(response)
    }

  def request[A: Encoder, B: Decoder](
      method: String,
      request: A
  ): Task[Either[Response.Error, B]] = {
    val nextId = RequestId(counter.incrementAndGet())
    val response = Task.create[Response] { (out, cb) =>
      val scheduled = out.scheduleOnce(Duration(0, "s")) {
        val json = Request(method, Some(request.asJson), nextId)
        activeServerRequests.put(nextId, cb)
        writer.write(json)
      }
      Cancelable { () =>
        scheduled.cancel()
        this.notify("$/cancelRequest", CancelParams(nextId.value))
      }
    }
    response.map {
      case Response.Empty =>
        Left(
          Response.invalidParams(
            s"Got empty response for request $request",
            nextId
          )
        )
      case err: Response.Error =>
        Left(err)
      case Response.Success(result, _) =>
        result.as[B].leftMap { err =>
          Response.invalidParams(err.toString, nextId)
        }
    }
  }

  override def showMessage(params: ShowMessageParams): Unit = {
    notify("window/showMessage", params)
  }

  override def logMessage(params: LogMessageParams): Unit = {
    notify("window/logMessage", params)
  }

  override def publishDiagnostics(
      publishDiagnostics: PublishDiagnostics
  ): Unit = {
    notify("textDocument/publishDiagnostics", publishDiagnostics)
  }
  def workspaceApplyEdit(
      params: ApplyWorkspaceEditParams
  ): Task[Either[Response.Error, ApplyWorkspaceEditResponse]] =
    request[ApplyWorkspaceEditParams, ApplyWorkspaceEditResponse](
      "workspace/applyEdit",
      params
    )
}
