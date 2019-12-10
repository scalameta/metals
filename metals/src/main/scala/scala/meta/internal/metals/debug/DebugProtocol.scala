package scala.meta.internal.metals.debug

import com.google.gson.JsonElement
import org.eclipse.lsp4j.{debug => dap}
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.IdentifiableMessage
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Try

object DebugProtocol {
  import scala.meta.internal.metals.JsonParser._
  val FirstMessageId = 1

  def copy(original: Source): Source = {
    val source = new Source
    source.setAdapterData(original.getAdapterData)
    source.setChecksums(original.getChecksums)
    source.setName(original.getName)
    source.setOrigin(original.getOrigin)
    source.setPath(original.getPath)
    source.setPresentationHint(original.getPresentationHint)
    source.setSourceReference(original.getSourceReference)
    source.setSources(original.getSources)
    source
  }

  def syntheticRequest(args: SetBreakpointsArguments): RequestMessage = {
    val request = new DebugRequestMessage
    request.setMethod("setBreakpoints")
    request.setParams(args.toJson)
    request
  }

  def syntheticResponse[A: ClassTag](
      request: RequestMessage,
      args: SetBreakpointsResponse
  ): ResponseMessage = {
    val response = new DebugResponseMessage
    response.setId(request.getId)
    response.setMethod(request.getMethod)
    response.setResult(args.toJson)
    response
  }

  def syntheticFailure(
      request: DebugResponseMessage,
      cause: String
  ): ResponseMessage = {
    val error = new ResponseError(ResponseErrorCode.InternalError, cause, null)

    val response = new DebugResponseMessage
    response.setId(request.getId)
    response.setMethod(request.getMethod)
    response.setError(error)
    response
  }

  object SyntheticMessage {
    def unapply(msg: IdentifiableMessage): Option[IdentifiableMessage] = {
      if (msg.getId == null) Some(msg)
      else None
    }
  }

  object InitializeRequest {
    def unapply(
        request: DebugRequestMessage
    ): Option[InitializeRequestArguments] = {
      if (request.getMethod != "initialize") None
      else parse[InitializeRequestArguments](request.getParams).toOption
    }
  }

  object SetBreakpointRequest {
    def unapply(request: RequestMessage): Option[SetBreakpointsArguments] = {
      if (request.getMethod != "setBreakpoints") None
      else parse[SetBreakpointsArguments](request.getParams).toOption
    }
  }

  object StackTraceResponse {
    def unapply(
        response: DebugResponseMessage
    ): Option[dap.StackTraceResponse] = {
      if (response.getMethod != "stackTrace") None
      else parse[dap.StackTraceResponse](response.getResult).toOption
    }
  }

  object RestartRequest {
    def unapply(request: RequestMessage): Option[DisconnectArguments] = {
      if (request.getMethod != "disconnect") None
      else {
        parse[DisconnectArguments](request.getParams)
          .filter(_.getRestart)
          .toOption
      }
    }
  }

  object OutputNotification {
    def unapply(notification: NotificationMessage): Boolean = {
      notification.getMethod == "output"
    }
  }

  def parseResponse[A: ClassTag](response: ResponseMessage): Try[A] = {
    parse[A](response.getResult)
  }

  def parse[A: ClassTag](params: Any): Try[A] = {
    params match {
      case json: JsonElement =>
        json.as[A]
      case _ =>
        scribe.debug(s"DAP error: $params is not a json")
        Failure(new IllegalStateException(s"$params is not a json"))
    }
  }
}
