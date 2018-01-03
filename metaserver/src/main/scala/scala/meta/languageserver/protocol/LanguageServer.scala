package scala.meta.languageserver.protocol

import java.io.OutputStream
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
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
    in: Observable[BaseProtocolMessage],
    out: OutputStream,
    services: Services,
    requestScheduler: Scheduler
) extends LazyLogging {
  private val writer = new MessageWriter(out)
  private val activeClientRequests: TrieMap[JsValue, Cancelable] = TrieMap.empty
  private val cancelNotification =
    Service.notification[JsValue]("$/cancelNotification") { id =>
      activeClientRequests.get(id) match {
        case None =>
          Task {
            logger.warn(s"Can't cancel request $id, no active request found.")
            Response.empty
          }
        case Some(request) =>
          Task {
            request.cancel()
            activeClientRequests.remove(id)
            Response.cancelled(id)
          }
      }
    }
  private val handlersByMethodName: Map[String, NamedJsonRpcService] =
    services.addService(cancelNotification).byMethodName

  def handleValidMessage(message: Message): Task[Response] = message match {
    case Notification(method, _) =>
      handlersByMethodName.get(method) match {
        case None =>
          Task {
            // Can't respond to invalid notifications
            logger.error(s"Unknown method '$method'")
            Response.empty
          }
        case Some(handler) =>
          handler.handle(message).onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Error handling notification $message", e)
              Response.empty
          }
      }
    case request @ Request(method, _, id) =>
      handlersByMethodName.get(method) match {
        case None => Task(Response.methodNotFound(method, id))
        case Some(handler) =>
          val response = handler.handle(request).onErrorRecover {
            case NonFatal(e) =>
              logger.error(s"Unhandled error handling request $request", e)
              Response.internalError(e.getMessage, request.id)
          }
          val runningResponse = response.runAsync(requestScheduler)
          activeClientRequests.put(Json.toJson(request.id), runningResponse)
          Task.fromFuture(runningResponse)
      }

  }

  def handleMessage(message: BaseProtocolMessage): Task[Response] =
    LanguageServer.parseMessage(message) match {
      case Left(parseError) => Task.now(parseError)
      case Right(json) =>
        json.validate[Message] match {
          case err: JsError => Task.now(Response.invalidRequest(err.toString))
          case JsSuccess(msg, _) => handleValidMessage(msg)
        }
    }

  def startTask: Task[Unit] =
    in.foreachL { msg =>
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

  def listen(): Unit = {
    val f = startTask.runAsync(requestScheduler)
    logger.info("Listening....")
    Await.result(f, Duration.Inf)
  }
}

object LanguageServer {
  def parseMessage(message: BaseProtocolMessage): Either[Response, JsValue] =
    try {
      Right(Json.parse(message.content))
    } catch {
      case e: JsonParseException =>
        Left(Response.parseError(e.getMessage))
    }
}
