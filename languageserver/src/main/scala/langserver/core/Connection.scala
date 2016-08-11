package langserver.core

import langserver.messages._
import langserver.types.Diagnostic
import play.api.libs.json.JsValue
import com.dhpcs.jsonrpc.JsonRpcResponseError

trait Connection {
  /**
   * A message request, that will also show an action and require a response
   * from the client.
   * 
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   * @param actions A short title like 'Retry', 'Open Log' etc.
   */
  def showMessage(tpe: Int, message: String, actions: String*): Unit
  
  /**
   * A notification sent to the client to show a message.
   * 
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def showMessage(tpe: Int, message: String): Unit
  
  /**
   * A notification sent to the client to log a message.
   * 
   * @param tpe One of MessageType values
   * @param message The message to display in the client
   */
  def logMessage(tpe: Int, message: String): Unit
  
  /**
   * A notification sent to the client to signal results of validation runs.
   */
  def publishDiagnostics(uri: String, diagnostics: Seq[Diagnostic]): Unit
  
  def onMethodCall(methodName: String, params: JsValue): Unit
  
  def sendResponse(id: Long, result: JsValue, error: Option[JsonRpcResponseError]): Unit
  
  def sendNotification(methodName: String, params: Option[JsValue]): Unit
}
