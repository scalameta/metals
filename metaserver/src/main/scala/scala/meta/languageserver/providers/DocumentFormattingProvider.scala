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
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

object DocumentFormattingProvider extends LazyLogging {
  def format(
      input: Input.VirtualFile,
      scalafmt: Formatter,
      cwd: AbsolutePath
  ): DocumentFormattingResult = {
    val fullDocumentRange = Range(
      start = Position(0, 0),
      end = Position(Int.MaxValue, Int.MaxValue)
    )
    val config = cwd.resolve(".scalafmt.conf")
    val edits = if (Files.isRegularFile(config.toNIO)) {
      val formattedContent = scalafmt.format(input.value, input.path, config)
      List(TextEdit(fullDocumentRange, formattedContent))
    } else {
      Nil
    }
    DocumentFormattingResult(edits)
  }
}
