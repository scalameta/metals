package scala.meta.languageserver.protocol

import com.typesafe.scalalogging.LazyLogging
import monix.eval.Task

class CompositeRequestService(requests: List[NamedRequestService])
    extends JsonRequestService
    with LazyLogging {
  private val map = requests.iterator.map(n => n.method -> n).toMap
  override def handleRequest(request: Request): Task[Response] =
    map.get(request.method) match {
      case None =>
        Task.now(
          Response.methodNotFound(
            s"Method '${request.method}' not found, expected one of ${map.keys.mkString(", ")}",
            request.id
          )
        )
      case Some(service) =>
        service.handleRequest(request)
    }
}
