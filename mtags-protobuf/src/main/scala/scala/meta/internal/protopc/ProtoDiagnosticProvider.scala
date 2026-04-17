package scala.meta.internal.protopc

import java.nio.file.Paths

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import scala.meta.internal.proto.binder.Binder
import scala.meta.internal.proto.diag.ProtoError
import scala.meta.internal.proto.diag.SourceFile
import scala.meta.internal.proto.parse.Parser
import scala.meta.pc.VirtualFileParams

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * Provides diagnostics (errors and warnings) for protobuf files.
 */
class ProtoDiagnosticProvider(
    compiler: ProtoMetalsCompiler,
    params: VirtualFileParams
) {

  def diagnostics(): List[Diagnostic] = {
    val source = new SourceFile(params.uri().toString, params.text())
    try {
      val file = Parser.parse(source)
      // Parse succeeded, check for name resolution errors
      val sourcePath =
        try {
          Paths.get(new java.net.URI(params.uri().toString))
        } catch {
          case _: Exception => null
        }
      val bindingResult =
        Binder.bindWithErrors(file, compiler.importResolver, sourcePath)
      bindingResult.errors().asScala.toList.map { error =>
        bindingErrorToDiagnostic(error, source)
      }
    } catch {
      case e: ProtoError =>
        List(protoErrorToDiagnostic(e))
      case NonFatal(e) =>
        // Unexpected error
        List(
          new Diagnostic(
            new Range(new Position(0, 0), new Position(0, 0)),
            s"Parse error: ${e.getMessage}",
            DiagnosticSeverity.Error,
            "protopc"
          )
        )
    }
  }

  private def protoErrorToDiagnostic(error: ProtoError): Diagnostic = {
    val startPos = new Position(error.line(), error.column())
    val endPos = new Position(error.line(), error.column() + 1)

    val diagnostic = new Diagnostic()
    diagnostic.setRange(new Range(startPos, endPos))
    diagnostic.setMessage(error.rawMessage())
    diagnostic.setSeverity(DiagnosticSeverity.Error)
    diagnostic.setSource("protopc")
    diagnostic
  }

  private def bindingErrorToDiagnostic(
      error: Binder.BindingError,
      source: SourceFile
  ): Diagnostic = {
    val startOffset = error.position()
    val endOffset = error.typeRef().endPosition()

    val startLine = source.offsetToLine(startOffset)
    val startCol = source.offsetToColumn(startOffset)
    val endLine = source.offsetToLine(endOffset)
    val endCol = source.offsetToColumn(endOffset)

    val startPos = new Position(startLine, startCol)
    val endPos = new Position(endLine, endCol)

    val diagnostic = new Diagnostic()
    diagnostic.setRange(new Range(startPos, endPos))
    diagnostic.setMessage(error.message())
    diagnostic.setSeverity(DiagnosticSeverity.Error)
    diagnostic.setSource("protopc")
    diagnostic
  }
}
