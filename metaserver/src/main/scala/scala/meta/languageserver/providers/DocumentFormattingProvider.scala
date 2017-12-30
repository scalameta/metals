package scala.meta.languageserver.providers

import java.nio.file.Files
import scala.meta.languageserver.Formatter
import scala.meta.languageserver.Configuration
import com.typesafe.scalalogging.LazyLogging
import langserver.messages.DocumentFormattingResult
import langserver.types.Position
import langserver.types.Range
import langserver.types.TextEdit
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath
import monix.reactive.Observable
import monix.eval.Task

class DocumentFormattingProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath
) extends LazyLogging {

  private lazy val scalafmt = Formatter.classloadScalafmt("1.3.0")

  private def formatterFromConfiguration: Task[Formatter] =
    configuration.take(1).lastL.map { conf =>
      if (conf.scalafmt.enabled) scalafmt
      else Formatter.noop
    }

  private def scalafmtConfigFromConfiguration: Task[AbsolutePath] =
    configuration.take(1).lastL.map { conf =>
      cwd.resolve(conf.scalafmt.confPath)
    }

  def format(
      input: Input.VirtualFile
  ): Task[DocumentFormattingResult] =
    formatterFromConfiguration.zip(scalafmtConfigFromConfiguration).map {
      case (formatter, config) =>
        val fullDocumentRange = Range(
          start = Position(0, 0),
          end = Position(Int.MaxValue, Int.MaxValue)
        )
        val edits = if (Files.isRegularFile(config.toNIO)) {
          val formattedContent =
            scalafmt.format(input.value, input.path, config)
          List(TextEdit(fullDocumentRange, formattedContent))
        } else {
          Nil
        }
        DocumentFormattingResult(edits)
    }

}
