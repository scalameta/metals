package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import langserver.messages.CodeActionRequest
import langserver.messages.CodeActionResult
import play.api.libs.json.Json

object CodeActionProvider extends LazyLogging {
  def codeActions(request: CodeActionRequest): CodeActionResult = {
    logger.info(Json.prettyPrint(Json.toJson(request)))
    CodeActionResult(Nil)
  }
}
