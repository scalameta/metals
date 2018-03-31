package scala.meta.metals.providers

import scala.meta.metals.Linter
import scala.meta.metals.Configuration
import com.typesafe.scalalogging.LazyLogging
import scala.{meta => m}
import scala.meta.metals.ScalametaEnrichments._
import org.langmeta.lsp.MonixEnrichments._
import org.langmeta.lsp.PublishDiagnostics
import org.langmeta.jsonrpc.JsonRpcClient
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.AbsolutePath
import org.langmeta.lsp.Diagnostic

class DiagnosticsProvider(
    configuration: Observable[Configuration],
    cwd: AbsolutePath
)(implicit s: Scheduler, client: JsonRpcClient)
    extends LazyLogging {
  private def latestConfig = configuration.toFunction0()
  private def scalafixDisabled: Boolean =
    !latestConfig().scalafix.enabled
  private def scalacDisabled: Boolean =
    !latestConfig().scalac.diagnostics.enabled

  lazy val linter = new Linter(configuration, cwd)

  def diagnostics(doc: m.Document): Task[Seq[PublishDiagnostics]] =
    diagnostics(m.Database(doc :: Nil))
  def diagnostics(db: m.Database): Task[Seq[PublishDiagnostics]] = {
    if (scalafixDisabled && scalacDisabled) Task(Nil)
    else
      Task.sequence {
        db.documents.map { document =>
          val uri = document.input.syntax

          val scalacDiagnostics: Seq[Diagnostic] =
            if (scalacDisabled) Nil
            else document.messages.map(_.toLSP("scalac"))

          val linterTask: Task[Seq[Diagnostic]] =
            if (scalafixDisabled) Task(Nil)
            else linter.linterMessages(document).map(_.getOrElse(Nil))

          linterTask.map { linterDiagnostics =>
            PublishDiagnostics(uri, scalacDiagnostics ++ linterDiagnostics)
          }
        }
      }
  }
}
