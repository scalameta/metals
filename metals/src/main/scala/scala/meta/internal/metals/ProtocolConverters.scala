package scala.meta.internal.metals

import scala.{meta => m}
import ch.epfl.scala.{bsp4j => b}
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.net.URI
import java.nio.file.Paths
import java.util
import org.eclipse.{lsp4j => l}
import scala.meta.inputs.Input
import scala.meta.io.AbsolutePath
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.mtags.Enrichments._

object ProtocolConverters {
  private val gson = new Gson()

  implicit class XtensionBuildTarget(buildTarget: b.BuildTarget) {
    def asScalaBuildTarget: b.ScalaBuildTarget = {
      gson.fromJson[b.ScalaBuildTarget](
        buildTarget.getData.asInstanceOf[JsonElement],
        classOf[b.ScalaBuildTarget]
      )
    }
  }

  implicit class XtensionEditDistance(result: Either[EmptyResult, m.Position]) {
    def foldResult[B](
        onPosition: m.Position => B,
        onUnchanged: () => B,
        onNoMatch: () => B
    ): B = result match {
      case Right(pos) => onPosition(pos)
      case Left(EmptyResult.Unchanged) => onUnchanged()
      case Left(EmptyResult.NoMatch) => onNoMatch()
    }
  }

  implicit class XtensionJavaList[A](lst: util.List[A]) {
    def map[B](fn: A => B): util.List[B] = {
      val out = new util.ArrayList[B]()
      val iter = lst.iterator()
      while (iter.hasNext) {
        out.add(fn(iter.next()))
      }
      out
    }
  }

  implicit class XtensionAbsolutePathBuffers(path: AbsolutePath) {
    def isSbtOrScala: Boolean = {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith(".sbt") ||
      filename.endsWith(".scala")
    }
    def toInputFromBuffers(buffers: Buffers): Input.VirtualFile = {
      buffers.get(path) match {
        case Some(text) => Input.VirtualFile(path.toString(), text)
        case None => path.toInput
      }
    }
  }
  implicit class XtensionStringUriProtocol(uri: String) {
    def toAbsolutePath: AbsolutePath =
      AbsolutePath(Paths.get(URI.create(uri)))
  }
  implicit class XtensionTextDocumentSemanticdb(textDocument: s.TextDocument) {
    def toInput: Input = Input.VirtualFile(textDocument.uri, textDocument.text)
    def definition(uri: String, symbol: String): Option[l.Location] = {
      textDocument.occurrences
        .find(o => o.role.isDefinition && o.symbol == symbol)
        .map { occ =>
          occ.toLocation(uri)
        }
    }
  }
  implicit class XtensionSeverityBsp(sev: b.DiagnosticSeverity) {
    def toLSP: l.DiagnosticSeverity =
      l.DiagnosticSeverity.forValue(sev.getValue)
  }
  implicit class XtensionPositionBSp(pos: b.Position) {
    def toLSP: l.Position =
      new l.Position(pos.getLine, pos.getCharacter)
  }
  implicit class XtensionRangeBsp(range: b.Range) {
    def toLSP: l.Range =
      new l.Range(range.getStart.toLSP, range.getEnd.toLSP)
  }

  implicit class XtensionRangeProtocol(range: s.Range) {
    def toLSP: l.Range = {
      val start = new l.Position(range.startLine, range.startCharacter)
      val end = new l.Position(range.endLine, range.endCharacter)
      new l.Range(start, end)
    }
    def encloses(other: l.Position): Boolean = {
      range.startLine <= other.getLine &&
      range.endLine >= other.getLine &&
      range.startCharacter <= other.getCharacter &&
      range.endCharacter > other.getCharacter
    }
    def encloses(other: l.Range): Boolean = {
      encloses(other.getStart) &&
      encloses(other.getEnd)
    }
  }
  implicit class XtensionSymbolOccurrenceProtocol(occ: s.SymbolOccurrence) {

    def toLocation(uri: String): l.Location = {
      val range = occ.range.getOrElse(s.Range(0, 0, 0, 0)).toLSP
      new l.Location(uri, range)
    }
    def encloses(pos: l.Position): Boolean =
      occ.range.isDefined &&
        occ.range.get.encloses(pos)

  }
  implicit class XtensionDiagnosticBsp(diag: b.Diagnostic) {
    def toLSP: l.Diagnostic =
      new l.Diagnostic(
        diag.getRange.toLSP,
        diag.getMessage,
        diag.getSeverity.toLSP,
        diag.getSource,
        diag.getCode
      )
  }

}
