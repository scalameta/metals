package scala.meta.languageserver.providers

import java.nio.file.Files
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Formatter
import scala.meta.languageserver.Uri
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.DocumentFormattingResult
import langserver.messages.TextDocumentFormattingRequest
import langserver.types.Position
import langserver.types.Range
import langserver.types.TextEdit
import org.langmeta.io.AbsolutePath

object DocumentFormattingProvider extends LazyLogging {
  def format(
      request: TextDocumentFormattingRequest,
      scalafmt: Formatter,
      buffers: Buffers,
      cwd: AbsolutePath
  ): DocumentFormattingResult = {
    val path = Uri.toPath(request.params.textDocument.uri).get
    val contents = buffers.read(path)
    val fullDocumentRange = Range(
      start = Position(0, 0),
      end = Position(Int.MaxValue, Int.MaxValue)
    )
    val config = cwd.resolve(".scalafmt.conf")
    val edits = if (Files.isRegularFile(config.toNIO)) {
      val formattedContent =
        scalafmt.format(contents, path.toString(), config)
      List(TextEdit(fullDocumentRange, formattedContent))
    } else {
      Nil
    }
    DocumentFormattingResult(edits)
  }
}
