package scala.meta.metals

import scala.meta.metals.ScalametaEnrichments._
import org.langmeta.lsp.Diagnostic
import org.langmeta.lsp.PublishDiagnostics
import org.langmeta.jsonrpc.JsonRpcClient
import scala.{meta => m}
import scala.meta.semanticdb
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.util.SemanticdbIndex
import org.langmeta.lsp.TextDocument.publishDiagnostics
import org.langmeta.{lsp => l}

class ScalacErrorReporter()(implicit connection: JsonRpcClient) {

  def reportErrors(
      mdb: m.Database
  ): Effects.PublishDiagnostics = {
    val messages = analyzeIndex(mdb)
    messages.foreach(publishDiagnostics.notify)
    Effects.PublishDiagnostics
  }
  private def analyzeIndex(mdb: m.Database): Seq[PublishDiagnostics] =
    analyzeIndex(
      EagerInMemorySemanticdbIndex(mdb, m.Sourcepath(Nil), m.Classpath(Nil))
    )
  private def analyzeIndex(index: SemanticdbIndex): Seq[PublishDiagnostics] =
    index.database.documents.map { d =>
      val uri = d.input.syntax
      PublishDiagnostics(uri, d.messages.map(toDiagnostic))
    }

  private def toDiagnostic(msg: semanticdb.Message): Diagnostic = {
    l.Diagnostic(
      range = msg.position.toRange,
      severity = Some(toSeverity(msg.severity)),
      code = None,
      source = Some("scalac"),
      message = msg.text
    )
  }

  private def toSeverity(
      s: semanticdb.Severity
  ): l.DiagnosticSeverity =
    s match {
      case semanticdb.Severity.Error => l.DiagnosticSeverity.Error
      case semanticdb.Severity.Warning => l.DiagnosticSeverity.Warning
      case semanticdb.Severity.Info => l.DiagnosticSeverity.Information
    }

}
