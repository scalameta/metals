package langserver.core

import com.dhpcs.jsonrpc.JsonRpcMessage
import com.dhpcs.jsonrpc.JsonRpcMessage.Params
import com.dhpcs.jsonrpc.JsonRpcMessage.ParamsOps._
import com.dhpcs.jsonrpc.JsonRpcRequestMessage
import com.dhpcs.jsonrpc.JsonRpcResponseErrorMessage
import com.dhpcs.jsonrpc.JsonRpcResponseMessage
import com.dhpcs.jsonrpc.JsonRpcResponseSuccessMessage
import monix.eval.Task
import play.api.libs.json.JsNull
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import play.api.libs.json._

trait Service[A, B] { self =>
  def method: String
  def handleRequest(request: A): Task[B]
}

class JsonRpcService(
    notifications: List[NotificationService],
    requests: List[RequestService]
) {
  
}
trait NotificationService extends Service[JsonRpcRequestMessage, Unit]
trait RequestService
    extends Service[JsonRpcRequestMessage, JsonRpcResponseMessage]

object Service {
  def notification[A](
      methodName: String
  )(f: A => Task[Unit]): Service[A, Unit] =
    request[A, Unit](methodName)(f)
  def request[A, B](methodName: String)(f: A => Task[B]): Service[A, B] =
    new Service[A, B] {
      override def method: String = methodName
      override def handleRequest(request: A): Task[B] = f(request)
    }

  private implicit val ParamsReads: Writes[Params] =
    params => params.unlift.fold(JsNull: JsValue)(Json.toJson(_))

  def toRequestService[A, B](
      service: Service[A, B]
  )(implicit reads: Reads[A], writes: Writes[B]): RequestService =
    new RequestService {
      override def method: String = service.method
      override def handleRequest(
          request: JsonRpcRequestMessage
      ): Task[JsonRpcResponseMessage] =
        Json.toJson(request.params).validate[A] match {
          case err: JsError =>
            Task.now(
              JsonRpcResponseErrorMessage.invalidRequest(err, request.id)
            )
          case JsSuccess(value, _) =>
            service.handleRequest(value).map { response =>
              JsonRpcResponseSuccessMessage(
                Json.toJson(response),
                request.id
              )
            }
        }
    }
  def toNotificationService[A](
      service: Service[A, Unit]
  )(implicit ev: Reads[A]): NotificationService = new NotificationService {
    override def method: String = service.method
    override def handleRequest(request: JsonRpcRequestMessage): Task[Unit] =
      Json
        .toJson(request.params)
        .asOpt[A]
        .fold(Task.unit)(service.handleRequest)
  }
}
