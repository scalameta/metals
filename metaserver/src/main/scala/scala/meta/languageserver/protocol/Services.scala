package scala.meta.languageserver.protocol

import scala.meta.languageserver.PlayJsonEnrichments._
import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import play.api.libs.json._

trait Service[A, B] {
  def handle(request: A): Task[B]
}
trait MethodName {
  def methodName: String
}

trait JsonRpcService extends Service[Message, Response]
trait NamedJsonRpcService extends JsonRpcService with MethodName

object Service extends LazyLogging {

  def request[A: Reads, B: Writes](method: String)(
      f: Service[A, Either[Response.Error, B]]
  ): NamedJsonRpcService = new NamedJsonRpcService {
    override def methodName: String = method
    override def handle(message: Message): Task[Response] = message match {
      case Request(`method`, params, id) =>
        params.getOrElse(JsNull).validate[A] match {
          case err: JsError =>
            Task(Response.invalidParams(err.show, id))
          case JsSuccess(value, _) =>
            f.handle(value).map {
              case Right(response) => Response.ok(Json.toJson(response), id)
              case Left(err) => err
            }
        }
      case Request(invalidMethod, _, id) =>
        Task(Response.methodNotFound(invalidMethod, id))
      case _ =>
        Task(Response.invalidRequest(s"Expected request, obtained $message"))
    }
  }

  def notification[A: Reads](method: String)(
      f: Service[A, Unit]
  ): NamedJsonRpcService =
    new NamedJsonRpcService {
      override def methodName: String = method
      private def fail(msg: String): Task[Response] = Task {
        logger.error(msg)
        Response.empty
      }
      override def handle(message: Message): Task[Response] = message match {
        case Notification(`method`, params) =>
          params.getOrElse(JsNull).validate[A] match {
            case err: JsError =>
              fail(s"Failed to parse notification $message. Errors: $err")
            case JsSuccess(value, _) =>
              f.handle(value).map(_ => Response.empty)
          }
        case Notification(invalidMethod, _) =>
          fail(s"Expected method '$method', obtained '$invalidMethod'")
        case request: Request =>
          fail(
            s"Expected notification with no ID, obtained request with id $request"
          )
      }
    }

}

object Services {
  val empty: Services = new Services(Nil)
}

class Services private (val services: List[NamedJsonRpcService]) {
  def request[A: Reads, B: Writes](method: String)(
      f: A => B
  ): Services = requestAsync[A, B](method)(request => Task(Right(f(request))))
  def requestAsync[A: Reads, B: Writes](method: String)(
      f: Service[A, Either[Response.Error, B]]
  ): Services = addService(Service.request[A, B](method)(f))
  def notification[A: Reads](method: String)(
      f: A => Unit
  ): Services = notificationAsync[A](method)(request => Task(f(request)))
  def notificationAsync[A: Reads](method: String)(
      f: Service[A, Unit]
  ): Services = addService(Service.notification[A](method)(f))

  def +(other: Services): Services =
    other.services.foldLeft(this)(_ addService _)
  def byMethodName: Map[String, NamedJsonRpcService] =
    services.iterator.map(s => s.methodName -> s).toMap
  def addService(service: NamedJsonRpcService): Services = {
    val duplicate = services.find(_.methodName == service.methodName)
    require(
      duplicate.isEmpty,
      s"Duplicate service handler for method ${duplicate.get.methodName}"
    )
    new Services(service :: services)
  }
}
