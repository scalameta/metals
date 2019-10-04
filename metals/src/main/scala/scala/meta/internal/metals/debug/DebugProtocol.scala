package scala.meta.internal.metals.debug

import com.google.gson.JsonElement
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage

import scala.util.Success

private[debug] object DebugProtocol {
  import scala.meta.internal.metals.JsonParser._

  object RestartRequest {
    def unapply(request: RequestMessage): Option[RequestMessage] = {
      if (request.getMethod != "disconnect") None
      else {
        request.getParams match {
          case json: JsonElement =>
            json.as[DisconnectArguments] match {
              case Success(args) if args.getRestart => Some(request)
              case _ => None
            }
          case _ => None
        }
      }
    }
  }

  object OutputNotification {
    def unapply(notification: NotificationMessage): Boolean = {
      notification.getMethod == "output"
    }
  }
}
