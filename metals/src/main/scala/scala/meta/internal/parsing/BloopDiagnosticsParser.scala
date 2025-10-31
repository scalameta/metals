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
  // parse error lines from sbt output and produce diagnostics based on them
  def getDiagnosticsFromErrors(
      errorLines: Array[String]
  ): Iterable[PublishDiagnosticsParams] = {
    // create List of diagnostic-related lines, each element for 1 diagnostic
    errorLines
      .foldLeft(List.empty[Vector[String]]) { case (accumulator, element) =>
        if (element.startsWith("/")) {
          // if a line starts with "/", we interpret it as a path to source
          // and as a start of a new group of lines for diagnostic
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
          // first line of each group follows a pattern path:line:errorText;
          split = head.split(":", 3)
          filepath <- split.headOption
          line1 <- split.lift(1).flatMap(_.toIntOption)
          line0 = line1 - 1
          errorText <- split.lift(2).map(_.strip())
          uriFilepath = Paths.get(filepath).toUri

          // find the line with a pointer to the start of the error
          pointerIndex = errors.indexWhere(
            """^\s*\^""".r.findPrefixOf(_).isDefined
          )
          (string, errorTextEnriched) =
            if (pointerIndex != -1)
              (
                errors(pointerIndex),
                // lines between the path and the pointer contain info about error
                (errorText +: errors.slice(1, pointerIndex + 1)).mkString("\n"),
              )
            else ("", errorText)
          startIndex = string.indexOf('^').max(0)

          positionStart = new Position(line0, startIndex)
          // sbt output does not contain a pointer to the end of the error,
          // therefore we highlight the whole line from the starting pointer
          positionEnd = new Position(line0, Integer.MAX_VALUE)
          range = new Range(positionStart, positionEnd)
          diagnostic = new Diagnostic(
            range,
            errorTextEnriched,
            DiagnosticSeverity.Error,
            "sbt",
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
