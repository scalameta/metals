package langserver.messages

import play.api.libs.json.JsObject

sealed trait Message
sealed trait Command extends Message

sealed trait Response extends Message
sealed trait ResultResponse extends Response

sealed trait Notification extends Message

case class InitializeRequest() extends Command
case class InitializeResult(capabilities: ServerCapabilities) extends ResultResponse

case object ShutdownRequest extends Command

case class ShowMessageRequest(params: ShowMessageRequestParams) extends Command

case class ExitNotification() extends Message

case class ShowMessageNotification() extends Notification
