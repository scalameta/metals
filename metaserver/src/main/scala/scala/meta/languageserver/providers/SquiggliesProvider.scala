package scala.meta.languageserver.providers

import scala.meta.languageserver.Linter
import scala.meta.languageserver.Configuration
import com.typesafe.scalalogging.LazyLogging
import scala.{meta => m}
import langserver.messages.PublishDiagnostics
import scala.meta.languageserver.ScalametaEnrichments._
import scala.tools.nsc.interpreter.OutputStream
import monix.eval.Task
import monix.reactive.Observable
import org.langmeta.AbsolutePath

class SquiggliesProvider(configuration: Observable[Configuration], cwd: AbsolutePath, stdout: OutputStream) extends LazyLogging {

  lazy val linter = new Linter(cwd, stdout)

  def squigglies(doc: m.Document): Task[Seq[PublishDiagnostics]] =
    squigglies(m.Database(doc :: Nil))
  def squigglies(db: m.Database): Task[Seq[PublishDiagnostics]] = for {
    config <- configuration.lastL
  } yield {
    db.documents.map { document =>
      val uri = document.input.syntax
      val compilerErrors = document.messages.map(_.toLSP)
      val scalafixErrors =
        if (config.scalafix.enable) linter.linterMessages(document)
        else Nil
      PublishDiagnostics(uri, compilerErrors ++ scalafixErrors)
    }
  }
}
