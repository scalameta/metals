package scala.meta.languageserver.providers

import scala.meta.languageserver.Linter
import com.typesafe.scalalogging.LazyLogging
import scala.{meta => m}
import langserver.messages.PublishDiagnostics
import scala.meta.languageserver.ScalametaEnrichments._

object SquiggliesProvider extends LazyLogging {
  def squigglies(doc: m.Document, linter: Linter): Seq[PublishDiagnostics] =
    squigglies(m.Database(doc :: Nil), linter)
  def squigglies(db: m.Database, linter: Linter): Seq[PublishDiagnostics] = {
    db.documents.map { document =>
      val uri = document.input.syntax
      val compilerErrors = document.messages.map(_.toLSP)
      val scalafixErrors = linter.linterMessages(document)
      val publish = PublishDiagnostics(uri, compilerErrors ++ scalafixErrors)
      publish
    }
  }
}
