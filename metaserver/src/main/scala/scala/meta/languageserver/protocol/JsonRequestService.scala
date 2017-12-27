package scala.meta.languageserver.protocol

import monix.eval.Task
import monix.execution.misc.NonFatal
import play.api.libs.json.JsError
import play.api.libs.json.JsNull
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import play.api.libs.json.Reads
import play.api.libs.json.Writes

trait JsonRequestService {
  def handleRequest(request: Request): Task[Response]
}
trait NamedRequestService extends JsonRequestService {
  def method: String
}
abstract class RequestService[A: Reads, B: Writes](val method: String)
    extends NamedRequestService {
  def handle(request: A): Task[Either[Response.Error, B]]
  override def handleRequest(request: Request): Task[Response] =
    request.params.getOrElse(JsNull).validate[A] match {
      case err: JsError =>
        Task.eval(request.toError(ErrorCode.InvalidParams, err.toString))
      case JsSuccess(value, _) =>
        handle(value)
          .map[Response] {
            case Right(response) =>
              Response.success(Json.toJson(response), request.id)
            case Left(err) =>
              err
          }
          .onErrorRecover {
            case NonFatal(e) =>
              request.toError(ErrorCode.InternalError, e.getMessage)
          }
    }
}
