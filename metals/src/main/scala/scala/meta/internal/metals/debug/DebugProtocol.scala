package scala.meta.internal.metals.debug

import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.DebugProxy.DebugMode

import com.google.gson.JsonElement
import org.eclipse.lsp4j.debug.CompletionsArguments
import org.eclipse.lsp4j.debug.DisconnectArguments
import org.eclipse.lsp4j.debug.InitializeRequestArguments
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.eclipse.lsp4j.debug.SetBreakpointsArguments
import org.eclipse.lsp4j.debug.SetBreakpointsResponse
import org.eclipse.lsp4j.debug.Source
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugNotificationMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugRequestMessage
import org.eclipse.lsp4j.jsonrpc.debug.messages.DebugResponseMessage
import org.eclipse.lsp4j.jsonrpc.messages.IdentifiableMessage
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.{debug => dap}
import org.eclipse.{lsp4j => l}

object DebugProtocol {
  import DapJsonParser._
  val FirstMessageId = 1

  val serverName = "dap-server"
  val clientName = "dap-client"

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

  def syntheticResponse(
      request: RequestMessage,
      args: SetBreakpointsResponse,
  ): ResponseMessage = {
    val response = new DebugResponseMessage
    response.setId(request.getId)
    response.setMethod(request.getMethod)
    response.setResult(args.toJson)
    response
  }

  def stacktraceOutputResponse(
      output: OutputEventArguments,
      location: l.Location,
  ): DebugNotificationMessage = {
    val source = new Source()
    source.setName(location.getUri().toAbsolutePath.filename)
    source.setPath(location.getUri())

    // seems lines here start at 1
    output.setLine(location.getRange().getStart().getLine() + 1)
    output.setSource(source)

    val response = new DebugNotificationMessage()
    response.setMethod("output")
    response.setParams(output.toJson)
    response
  }

  def syntheticFailure(
      request: DebugResponseMessage,
      cause: String,
  ): ResponseMessage = {
    val error = new ResponseError(ResponseErrorCode.InternalError, cause, null)

    val response = new DebugResponseMessage
    response.setId(request.getId)
    response.setMethod(request.getMethod)
    response.setError(error)
    response
  }

  object EmptyResponse {
    def apply(initialize: DebugRequestMessage): DebugResponseMessage = {
      val response = new DebugResponseMessage
      response.setId(initialize.getId())
      response.setMethod(initialize.getMethod())
      response
    }
  }

  object SyntheticMessage {
    def unapply(msg: IdentifiableMessage): Option[IdentifiableMessage] = {
      if (msg.getId == null) Some(msg)
      else None
    }
  }

  object ErrorOutputNotification {
    def unapply(
        notification: NotificationMessage
    ): Option[OutputEventArguments] = {
      if (notification.getMethod != "output") None
      else
        parse[OutputEventArguments](notification.getParams).toOption
          .filter(_.getCategory() == "stderr")
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

  object LaunchRequest {
    def unapply(request: DebugRequestMessage): Option[DebugMode] = {
      if (request.getMethod != "launch") None
      else {
        parse[java.util.Map[String, Object]](request.getParams).toOption
          .map { map =>
            map.asScala.get("noDebug") match {
              case Some(value: java.lang.Boolean) if value => DebugMode.Disabled
              case Some(value: java.lang.Boolean) if !value => DebugMode.Enabled
              case _ => DebugMode.Enabled
            }
          }
      }
    }
  }

  object ConfigurationDone {
    def unapply(
        request: DebugRequestMessage
    ): Option[dap.ConfigurationDoneArguments] = {
      if (request.getMethod != "configurationDone") None
      else if (request.getParams() == null)
        Some(new dap.ConfigurationDoneArguments())
      else parse[dap.ConfigurationDoneArguments](request.getParams).toOption

    }
  }

  object TerminateRequest {
    def unapply(
        request: DebugRequestMessage
    ): Option[dap.TerminateArguments] = {
      if (request.getMethod != "terminate") None
      else
        parse[dap.TerminateArguments](request.getParams).toOption
    }
  }

  object SetBreakpointRequest {
    def unapply(request: RequestMessage): Option[SetBreakpointsArguments] = {
      if (request.getMethod != "setBreakpoints") None
      else parse[SetBreakpointsArguments](request.getParams).toOption
    }
  }

  object CompletionRequest {
    def unapply(request: RequestMessage): Option[CompletionsArguments] = {
      if (request.getMethod != "completions") None
      else parse[CompletionsArguments](request.getParams).toOption
    }
  }

  object HotCodeReplace {
    def unapply(request: RequestMessage): Option[RequestMessage] = {
      if (request.getMethod != "redefineClasses") None
      else Some(request)
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

  object DisconnectRequest {
    def unapply(request: DebugRequestMessage): Option[DisconnectArguments] = {
      if (request.getMethod != DisconnectRequest.name) None
      else {
        parse[DisconnectArguments](request.getParams).toOption
      }
    }
    val name = "disconnect"
  }

  object OutputNotification {
    def unapply(
        notification: NotificationMessage
    ): Option[OutputEventArguments] = {
      if (notification.getMethod != "output") None
      else parse[OutputEventArguments](notification.getParams).toOption
    }
  }

  def parseResponse[A: ClassTag](response: ResponseMessage): Option[A] = {
    parse[A](response.getResult).toOption
  }

  def parse[A: ClassTag](params: Any): Try[A] = {
    params match {
      case a: A => Success(a)
      case json: JsonElement =>
        json.as[A]
      case _ =>
        scribe.debug(s"DAP error: $params is not a json")
        Failure(new IllegalStateException(s"$params is not a json"))
    }
  }
}
