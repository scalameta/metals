package org.langmeta.jsonrpc

import com.typesafe.scalalogging.LazyLogging
import com.typesafe.scalalogging.Logger
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.syntax._
import monix.eval.Task

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
  ): NamedJsonRpcService = notification[A](method, logger)(f)

  def notification[A: Decoder](method: String, logger: Logger)(
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

  def request[A, B](endpoint: Endpoint[A, B])(f: A => B): Services =
    requestAsync[A, B](endpoint)(new Service[A, Either[Response.Error, B]] {
      def handle(request: A): Task[Either[Response.Error, B]] =
        Task(Right(f(request)))
    })

  def requestAsync[A, B](
      endpoint: Endpoint[A, B]
  )(f: Service[A, Either[Response.Error, B]]): Services =
    addService(
      Service.request[A, B](endpoint.method)(f)(
        endpoint.decoderA,
        endpoint.encoderB
      )
    )

  def notification[A](endpoint: Endpoint[A, Unit])(f: A => Unit): Services =
    notificationAsync[A](endpoint)(new Service[A, Unit] {
      def handle(request: A): Task[Unit] = Task(f(request))
    })

  def notificationAsync[A](
      endpoint: Endpoint[A, Unit]
  )(f: Service[A, Unit]): Services =
    addService(Service.notification[A](endpoint.method)(f)(endpoint.decoderA))

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
