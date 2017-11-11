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
import metaconfig.ConfDecoder
import monix.reactive.Observable
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams

import scala.collection.JavaConverters._

class Linter(
    cwd: AbsolutePath,
    out: OutputStream,
    client: LanguageClient,
    semanticdbs: Observable[m.Database]
) {
  val linter: Observable[Unit] =
    semanticdbs.map { mdb =>
      val index =
        EagerInMemorySemanticdbIndex(mdb, m.Sourcepath(Nil), m.Classpath(Nil))
      val messages = analyzeIndex(index)
      messages.foreach(client.publishDiagnostics)
    }

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(
      filename: String,
      contents: String
  ): Seq[PublishDiagnosticsParams] = {
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

  private def analyzeIndex(index: SemanticdbIndex): Seq[PublishDiagnosticsParams] =
    withConfig { configInput =>
      val lazyIndex = lazySemanticdbIndex(index)
      val configDecoder = ScalafixReflect.fromLazySemanticdbIndex(lazyIndex)
      val (rule, config) =
        ScalafixConfig.fromInput(configInput, lazyIndex)(configDecoder).get
      val results: Seq[PublishDiagnosticsParams] = index.database.documents.map { d =>
        val filename = RelativePath(d.input.syntax)
        val tree = Parser.parse(d).get
        val ctx = RuleCtx.applyInternal(tree, config)
        val patches = rule.fixWithNameInternal(ctx)
        val diagnostics = for {
          (name, patch) <- patches.toIterator
          msg <- Patch.lintMessagesInternal(patch)
        } yield toDiagnostic(name, msg)
        new PublishDiagnosticsParams(s"file:${cwd.resolve(filename)}", diagnostics.toSeq.asJava)
      }

      // megaCache needs to die, if we forget this we will read stale
      // snapshots of filenames if using m.Input.File.slurp
      // https://github.com/scalameta/scalameta/issues/1068
      PlatformTokenizerCache.megaCache.clear()

      if (results.isEmpty) {
        client.showMessage(new MessageParams(
          MessageType.Warning,
          "Ran scalafix but found no lint messages :("
        ))
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

  private def toDiagnostic(name: RuleName, msg: LintMessage): Diagnostic = {
    new Diagnostic(
      msg.position.toRange,
      msg.message,
      toSeverity(msg.category.severity),
      "scalafix",
      msg.category.key(name)
    )
  }

  private def toSeverity(s: LintSeverity): DiagnosticSeverity = s match {
    case LintSeverity.Error => DiagnosticSeverity.Error
    case LintSeverity.Warning => DiagnosticSeverity.Warning
    case LintSeverity.Info => DiagnosticSeverity.Information
  }

}
