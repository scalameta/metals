package scala.meta.languageserver.providers

import scala.meta.languageserver.Linter
import scala.meta.languageserver.Configuration
import com.typesafe.scalalogging.LazyLogging
import scala.{meta => m}
import langserver.messages.PublishDiagnostics
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.MonixEnrichments._
import scala.tools.nsc.interpreter.OutputStream
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.AbsolutePath

class SquiggliesProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath
)(implicit s: Scheduler)
    extends LazyLogging {
  private val isEnabled: () => Boolean =
    configuration.map(_.scalafix.enabled).toFunction0()

  lazy val linter = new Linter(cwd)

  def squigglies(doc: m.Document): Task[Seq[PublishDiagnostics]] =
    squigglies(m.Database(doc :: Nil))
  def squigglies(db: m.Database): Task[Seq[PublishDiagnostics]] = Task.eval {
    if (!isEnabled()) Nil
    else {
      db.documents.map { document =>
        val uri = document.input.syntax
        val compilerErrors = document.messages.map(_.toLSP)
        val scalafixErrors = linter.linterMessages(document)
        PublishDiagnostics(uri, compilerErrors ++ scalafixErrors)
      }
    }
  }
}
