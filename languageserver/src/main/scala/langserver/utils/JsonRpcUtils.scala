package langserver.utils

import play.api.libs.json._

/**
 * Utility for messages that use the same parameter type in different requests.
 *
 * The JsonRpc library expects non-overlapping types in a command server object, but
 * the language server re-uses such parameters in different requests.
 *
 * @see https://github.com/dhpcs/play-json-rpc/issues/2
 */
object JsonRpcUtils {

  /**
   * Create a format that reads and writes as a different type. Most useful for
   * providing different types in the JsonRpc library, even though the serialized form
   * is the same.
   *
   * Since the library expects non-overlapping types in the MessageFormat structure, you
   * can work around that using this little helper function
   *
   * {{{
   *  case class TextDocumentCompletionCommand(positionParams: TextDocumentPositionParams) extends ServerCommand
   *   case class TextDocumentDefinitionCommand(positionParams: TextDocumentPositionParams) extends ServerCommand
   *
   *  object ServerCommand extends CommandCompanion[ServerCommand] {
   *    override val CommandFormats = {
   *    implicit val positionParamsFormat = Json.format[TextDocumentPositionParams]
   *      Message.MessageFormats(
   *          ..
   *          "textDocument/completion" -> valueFormat(TextDocumentCompletionCommand)(_.positionParams),
   *          "textDocument/definition" -> valueFormat(TextDocumentDefinitionCommand)(_.positionParams),
   *         )
   * }}}
   */
   def valueFormat[A, B: OFormat](apply: B => A)(unapply: A => B): OFormat[A] = new OFormat[A] {
    override def reads(json: JsValue) = Reads.of[B].reads(json).map(apply(_))
    override def writes(o: A) = Writes.of[B].writes(unapply(o)).as[JsObject]
  }
}
