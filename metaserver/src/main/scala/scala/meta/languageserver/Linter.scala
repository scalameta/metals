package scala.meta.languageserver

import java.io.PrintStream
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.languageserver.ScalametaEnrichments._
import scala.tools.nsc.interpreter.OutputStream
import scala.{meta => m}
import scalafix._
import scalafix.internal.config.LazySemanticdbIndex
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.config.ScalafixReporter
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.languageserver.ScalafixEnrichments._
import scalafix.lint.LintSeverity
import scalafix.patch.Patch
import scalafix.reflect.ScalafixReflect
import scalafix.rule.RuleCtx
import scalafix.rule.RuleName
import scalafix.util.SemanticdbIndex
import langserver.core.Connection
import langserver.messages.MessageType
import langserver.messages.PublishDiagnostics
import langserver.{types => l}
import metaconfig.ConfDecoder
import monix.reactive.Observable
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

class Linter(
    cwd: AbsolutePath,
    out: OutputStream,
    connection: Connection,
    semanticdbs: Observable[m.Database]
) {
  val linter: Observable[Unit] =
    semanticdbs.map { mdb =>
      val index =
        EagerInMemorySemanticdbIndex(mdb, m.Sourcepath(Nil), m.Classpath(Nil))
      val messages = analyzeIndex(index)
      messages.foreach(connection.sendNotification)
    }

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(
      filename: String,
      contents: String
  ): Seq[PublishDiagnostics] = {
    analyzeIndex(
      EagerInMemorySemanticdbIndex(
        m.Database(
          m.Document(
            m.Input.VirtualFile(filename, contents),
            "scala212",
            Nil,
            Nil,
            Nil,
            Nil
          ) :: Nil
        ),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
    )
  }

  private def analyzeIndex(index: SemanticdbIndex): Seq[PublishDiagnostics] =
    withConfig { configInput =>
      val lazyIndex = lazySemanticdbIndex(index)
      val configDecoder = ScalafixReflect.fromLazySemanticdbIndex(lazyIndex)
      val (rule, config) =
        ScalafixConfig.fromInput(configInput, lazyIndex)(configDecoder).get
      val results: Seq[PublishDiagnostics] = index.database.documents.map { d =>
        val tree = Parser.parse(d).get
        val ctx = RuleCtx.applyInternal(tree, config)
        val patches = rule.fixWithNameInternal(ctx)
        val diagnostics =
          Patch.lintMessagesInternal(patches, ctx).map(toDiagnostic)
        val uri = d.input.syntax
        PublishDiagnostics(uri, diagnostics)
      }

      // megaCache needs to die, if we forget this we will read stale
      // snapshots of filenames if using m.Input.File.slurp
      // https://github.com/scalameta/scalameta/issues/1068
      PlatformTokenizerCache.megaCache.clear()

      if (results.isEmpty) {
        connection.showMessage(
          MessageType.Warning,
          "Ran scalafix but found no lint messages :("
        )
      }
      results
    }

  private def withConfig[T](f: m.Input => Seq[T]): Seq[T] =
    configFile match {
      case None =>
        Nil
      case Some(configInput) =>
        f(configInput)
    }

  private def configFile: Option[m.Input] = ScalafixConfig.auto(cwd)

  private def lazySemanticdbIndex(index: SemanticdbIndex): LazySemanticdbIndex =
    new LazySemanticdbIndex(
      _ => Some(index),
      ScalafixReporter.default.copy(outStream = new PrintStream(out))
    )

  private def toDiagnostic(msg: LintMessage): l.Diagnostic = {
    l.Diagnostic(
      range = msg.position.toRange,
      severity = Some(toSeverity(msg.category.severity)),
      code = Some(msg.category.id),
      source = Some("scalafix"),
      message = msg.message
    )
  }

  private def toSeverity(s: LintSeverity): Int = s match {
    case LintSeverity.Error => l.DiagnosticSeverity.Error
    case LintSeverity.Warning => l.DiagnosticSeverity.Warning
    case LintSeverity.Info => l.DiagnosticSeverity.Information
  }

}
