package scala.meta.lsp

import java.io.OutputStream
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import com.fasterxml.jackson.core.JsonParseException
import com.typesafe.scalalogging.LazyLogging
import langserver.core.MessageWriter
import monix.eval.Task
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.reactive.Observable
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.Json

final class LanguageServer(
    in: Observable[LSPMessage],
    out: OutputStream,
    notifications: JsonNotificationService,
    requests: JsonRequestService,
    requestScheduler: Scheduler
) extends LazyLogging {
  private val writer = new MessageWriter(out)
  private val activeClientRequests: TrieMap[JsValue, Cancelable] = TrieMap.empty

  def handleValidMessage(message: Message): Task[Response] =
    message match {
      case request: Request =>
        val runningRequest = requests
          .handleRequest(request)
          .onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Unhandled error handling request $request", e)
              Response.internalError(e.getMessage, request.id)
          }
          .runAsync(requestScheduler)
        activeClientRequests.put(Json.toJson(request.id), runningRequest)
        Task.fromFuture(runningRequest)
      case notification: Notification =>
        notification match {
          case Notification("$/cancelNotification", Some(id)) =>
            activeClientRequests.get(id) match {
              case None =>
                Task.now(Response.empty)
              case Some(request) =>
                Task.eval {
                  request.cancel()
                  activeClientRequests.remove(id)
                  Response.cancelled(id)
                }
            }
          case _ =>
            notifications
              .handleNotification(notification)
              .map(_ => Response.empty)
        }
    }

  def handleMessage(message: LSPMessage): Task[Response] =
    LanguageServer.parseMessage(message) match {
      case Left(parseError) => Task.now(parseError)
      case Right(json) =>
        json.validate[Message] match {
          case err: JsError => Task.now(Response.invalidRequest(err.toString))
          case JsSuccess(msg, _) => handleValidMessage(msg)
        }
    }

  def start: Task[Unit] = in.foreachL { msg =>
    handleMessage(msg)
      .map {
        case Response.Empty => ()
        case x: Response.Success => writer.write(x)
        case x: Response.Error => writer.write(x)
      }
      .onErrorRecover {
        case NonFatal(e) =>
          logger.error("Unhandled error", e)
      }
      .runAsync(requestScheduler)
  }
}

object LanguageServer {
  def parseMessage(message: LSPMessage): Either[Response, JsValue] =
    try {
      Right(Json.parse(message.content))
    } catch {
      case e: JsonParseException =>
        Left(Response.parseError(e.getMessage))
    }
}
