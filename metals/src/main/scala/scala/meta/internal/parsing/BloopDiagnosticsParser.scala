package scala.meta.internal.parsing

import java.nio.file.Paths

import scala.jdk.CollectionConverters.SeqHasAsJava

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range

case class FileDiagnostic(filepath: String, diagnostic: Diagnostic)

object BloopDiagnosticsParser {
  def getDiagnosticsFromErrors(
      errorLines: Array[String]
  ): Iterable[PublishDiagnosticsParams] = {
    errorLines
      .foldLeft(List.empty[Vector[String]]) { case (accumulator, element) =>
        if (element.startsWith("/")) {
          accumulator.prepended(Vector(element))
        } else {
          accumulator
            .drop(1)
            .prepended(
              accumulator.headOption.fold(Vector(element))(_.prepended(element))
            )
        }
      }
      .flatMap(errorsR => {
        val errors = errorsR.reverse
        for {
          head <- errors.headOption
          split = head.split(":", 3)
          filepath <- split.headOption
          line1 <- split.lift(1).flatMap(_.toIntOption)
          line0 = line1 - 1
          errorText <- split.lift(2).map(_.strip())
          uriFilepath = Paths.get(filepath).toUri

          pointerIndex = errors.indexWhere(
            """^\s*\^""".r.findPrefixOf(_).isDefined
          )
          (string, errorTextEnriched) =
            if (pointerIndex != -1)
              (
                errors(pointerIndex),
                (errorText +: errors.slice(1, pointerIndex + 1)).mkString("\n"),
              )
            else ("", errorText)
          startIndex = string.indexOf('^').max(0)

          positionStart = new Position(line0, startIndex)
          positionEnd = new Position(line0, Integer.MAX_VALUE)
          range = new Range(positionStart, positionEnd)
          diagnostic = new Diagnostic(
            range,
            errorTextEnriched,
            DiagnosticSeverity.Error,
            uriFilepath.toString,
          )
        } yield FileDiagnostic(uriFilepath.toString, diagnostic)
      })
      .groupBy(_.filepath)
      .map { case (uriFilepath, diagnostics) =>
        new PublishDiagnosticsParams(
          uriFilepath,
          diagnostics.map(_.diagnostic).asJava,
        )
      }
  }
}
