package scala.meta.languageserver.protocol

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax._

trait Service[A, B] {
  def handle(request: A): Task[B]
}
trait MethodName {
  def methodName: String
}

trait JsonRpcService extends Service[Message, Response]
trait NamedJsonRpcService extends JsonRpcService with MethodName

object Service extends LazyLogging {

  def request[A: Decoder, B: Encoder](method: String)(
      f: Service[A, Either[Response.Error, B]]
  ): NamedJsonRpcService = new NamedJsonRpcService {
    override def methodName: String = method
    override def handle(message: Message): Task[Response] = message match {
      case Request(`method`, params, id) =>
        params.getOrElse(Json.Null).as[A] match {
          case Left(err) =>
            Task(Response.invalidParams(err.toString, id))
          case Right(value) =>
            f.handle(value).map {
              case Right(response) => Response.ok(response.asJson, id)
              // Service[A, ...] doesn't have access to the request ID so
              // by convention it's OK to set the ID to null by default
              // and we fill it in here instead.
              case Left(err) => err.copy(id = id)
            }
        }
      case Request(invalidMethod, _, id) =>
        Task(Response.methodNotFound(invalidMethod, id))
      case _ =>
        Task(Response.invalidRequest(s"Expected request, obtained $message"))
    }
  }

  def notification[A: Decoder](method: String)(
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
          params.getOrElse(Json.Null).as[A] match {
            case Left(err) =>
              fail(s"Failed to parse notification $message. Errors: $err")
            case Right(value) =>
              f.handle(value).map(_ => Response.empty)
          }
        case Notification(invalidMethod, _) =>
          fail(s"Expected method '$method', obtained '$invalidMethod'")
        case _ =>
          fail(s"Expected notification, obtained $message")
      }
    }
}

object Services {
  val empty: Services = new Services(Nil)
}

class Services private (val services: List[NamedJsonRpcService]) {

  def request[A: Decoder, B: Encoder](method: String)(
      f: A => B
  ): Services =
    requestAsync[A, B](method)(request => Task(Right(f(request))))

  def requestAsync[A: Decoder, B: Encoder](method: String)(
      f: Service[A, Either[Response.Error, B]]
  ): Services =
    addService(Service.request[A, B](method)(f))

  def notification[A: Decoder](method: String)(
      f: A => Unit
  ): Services =
    notificationAsync[A](method)(request => Task(f(request)))

  def notificationAsync[A: Decoder](method: String)(
      f: Service[A, Unit]
  ): Services =
    addService(Service.notification[A](method)(f))

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
