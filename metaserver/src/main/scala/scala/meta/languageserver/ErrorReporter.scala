package scala.meta.languageserver

import scala.meta.languageserver.ScalametaEnrichments._
import scala.{meta => m}
import scala.meta.semanticdb
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.util.SemanticdbIndex
import langserver.core.Connection
import langserver.messages.PublishDiagnostics
import langserver.{types => l}

class ScalacErrorReporter(
    connection: Connection,
) {

  def reportErrors(
      mdb: m.Database
  ): Effects.PublishScalacDiagnostics = {
    val messages = analyzeIndex(mdb)
    messages.foreach(connection.sendNotification)
    Effects.PublishScalacDiagnostics
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

  private def toDiagnostic(msg: semanticdb.Message): l.Diagnostic = {
    l.Diagnostic(
      range = msg.position.toRange,
      severity = Some(toSeverity(msg.severity)),
      code = None,
      source = Some("scalac"),
      message = msg.text
    )
  }

  private def toSeverity(s: semanticdb.Severity): l.DiagnosticSeverity =
    s match {
      case semanticdb.Severity.Error => l.DiagnosticSeverity.Error
      case semanticdb.Severity.Warning => l.DiagnosticSeverity.Warning
      case semanticdb.Severity.Info => l.DiagnosticSeverity.Information
    }

}
