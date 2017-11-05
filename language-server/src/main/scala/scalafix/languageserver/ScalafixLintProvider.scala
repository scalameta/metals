package scalafix.languageserver

import java.io.PrintStream
import java.nio.file.Files
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.languageserver.DiagnosticsReport
import scala.meta.languageserver.Parser
import scala.tools.nsc.interpreter.OutputStream
import scala.{meta => m}
import scalafix._
import scalafix.internal.config.LazySemanticdbIndex
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.config.ScalafixReporter
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.lint.LintSeverity
import scalafix.patch.Patch
import scalafix.reflect.ScalafixReflect
import scalafix.rule.RuleCtx
import scalafix.rule.RuleName
import scalafix.util.SemanticdbIndex
import langserver.core.Connection
import langserver.messages.MessageType
import langserver.{types => l}
import metaconfig.ConfDecoder
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

class ScalafixLintProvider(
    cwd: AbsolutePath,
    out: OutputStream,
    connection: Connection
) {
  def onSemanticdbPath(path: AbsolutePath): Seq[DiagnosticsReport] = {
    // NOTE(olafur): when we have multiple consumers of .semanticdb files
    // like DefinitionProvider/ReferenceProvider then we should move this out of the ScalafixService
    val bytes = Files.readAllBytes(path.toNIO)
    val sdb = s.Database.parseFrom(bytes)
    val mdb = sdb.toDb(None)
    val index =
      EagerInMemorySemanticdbIndex(mdb, m.Sourcepath(Nil), m.Classpath(Nil))
    onNewSemanticdb(index)
  }

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(
      filename: String,
      contents: String
  ): Seq[DiagnosticsReport] = {
    onNewSemanticdb(
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

  private def onNewSemanticdb(index: SemanticdbIndex): Seq[DiagnosticsReport] =
    withConfig { configInput =>
      val lazyIndex = lazySemanticdbIndex(index)
      val configDecoder = ScalafixReflect.fromLazySemanticdbIndex(lazyIndex)
      val (rule, config) =
        ScalafixConfig.fromInput(configInput, lazyIndex)(configDecoder).get
      val results: Seq[DiagnosticsReport] = index.database.documents.map { d =>
        val filename = RelativePath(d.input.syntax)
        val tree = Parser.parse(d).get
        val ctx = RuleCtx(tree, config)
        val patches = rule.fixWithName(ctx)
        val diagnostics = for {
          (name, patch) <- patches.toIterator
          msg <- Patch.lintMessages(patch)
        } yield toDiagnostic(name, msg)
        DiagnosticsReport(filename, diagnostics.toSeq)
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

  private def configFile: Option[m.Input] = ScalafixConfig.auto(cwd)
  private def withConfig[T](f: m.Input => Seq[T]): Seq[T] =
    configFile match {
      case None =>
        connection.showMessage(
          MessageType.Warning,
          s"Missing ${cwd.resolve(".scalafix.conf")}"
        )
        Nil
      case Some(configInput) =>
        f(configInput)
    }

  private def lazySemanticdbIndex(index: SemanticdbIndex): LazySemanticdbIndex =
    new LazySemanticdbIndex(
      _ => Some(index),
      ScalafixReporter.default.copy(outStream = new PrintStream(out))
    )

  private def toDiagnostic(name: RuleName, msg: LintMessage): l.Diagnostic = {
    l.Diagnostic(
      range = toRange(msg.position),
      severity = Some(toSeverity(msg.category.severity)),
      code = Some(msg.category.key(name)),
      source = Some("scalafix"),
      message = msg.message
    )
  }

  private def toSeverity(s: LintSeverity): Int = s match {
    case LintSeverity.Error => l.DiagnosticSeverity.Error
    case LintSeverity.Warning => l.DiagnosticSeverity.Warning
    case LintSeverity.Info => l.DiagnosticSeverity.Information
  }

  private def toRange(pos: m.Position): l.Range = l.Range(
    l.Position(line = pos.startLine, character = pos.startColumn),
    l.Position(line = pos.endLine, character = pos.endColumn)
  )
}
