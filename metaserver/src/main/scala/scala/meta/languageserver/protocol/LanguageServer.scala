package scala.meta.languageserver.protocol

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import com.fasterxml.jackson.core.JsonParseException
import com.typesafe.scalalogging.LazyLogging
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
    client: LanguageClient,
    services: Services,
    requestScheduler: Scheduler
) extends LazyLogging {
  private val activeClientRequests: TrieMap[JsValue, Cancelable] = TrieMap.empty
  private val cancelNotification =
    Service.notification[JsValue]("$/cancelRequest") { id =>
      activeClientRequests.get(id) match {
        case None =>
          Task {
            logger.warn(s"Can't cancel request $id, no active request found.")
            Response.empty
          }
        case Some(request) =>
          Task {
            logger.info(s"Cancelling request $id")
            request.cancel()
            activeClientRequests.remove(id)
            Response.cancelled(id)
          }
      }
    }
  private val handlersByMethodName: Map[String, NamedJsonRpcService] =
    services.addService(cancelNotification).byMethodName

  def handleValidMessage(message: Message): Task[Response] = message match {
    case response: Response =>
      Task {
        client.clientRespond(response)
        Response.empty
      }
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
        case None =>
          Task {
            logger.info(s"Method not found '$method'")
            Response.methodNotFound(method, id)
          }
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
        .map(client.serverRespond)
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
