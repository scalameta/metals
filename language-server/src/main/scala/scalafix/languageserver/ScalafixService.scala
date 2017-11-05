// In scalafix package to access private[scalafix] methods.
package scalafix.languageserver

import java.io.PrintStream
import java.nio.file.Files
import scala.meta.internal.tokenizers.PlatformTokenizerCache
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

case class ScalafixResult(path: RelativePath, diagnostics: Seq[l.Diagnostic])

class ScalafixService(cwd: AbsolutePath,
                      out: OutputStream,
                      connection: Connection) {
  def configFile: Option[m.Input] = ScalafixConfig.auto(cwd)
  def lazySemanticdbIndex(index: SemanticdbIndex): LazySemanticdbIndex =
    new LazySemanticdbIndex(
      _ => Some(index),
      ScalafixReporter.default.copy(outStream = new PrintStream(out)))
  def onNewSemanticdb(path: AbsolutePath): Seq[ScalafixResult] =
    onNewSemanticdb(s.Database.parseFrom(Files.readAllBytes(path.toNIO)))
  def onNewSemanticdb(database: s.Database): Seq[ScalafixResult] =
    onNewSemanticdb(database.toDb(None))
  def onNewSemanticdb(database: m.Database): Seq[ScalafixResult] = {
    onNewSemanticdb(
      EagerInMemorySemanticdbIndex(database,
                                   m.Sourcepath(Nil),
                                   m.Classpath(Nil)))
  }

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(filename: String,
                       contents: String): Seq[ScalafixResult] = {
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
      ))
  }

  // NOTE throws exception on failure.
  def onNewSemanticdb(index: SemanticdbIndex): Seq[ScalafixResult] =
    configFile match {
      case None =>
        connection.showMessage(
          MessageType.Info,
          s"Missing .scalafix.conf in working directory $cwd")
        Nil
      case Some(configInput) =>
        val lazyIndex = lazySemanticdbIndex(index)
        val (rule, config) = ScalafixConfig
          .fromInput(
            configInput,
            lazyIndex,
            extraRules = Nil // Can pass in List("RemoveUnusedImports")
          )(ScalafixReflect.fromLazySemanticdbIndex(lazyIndex))
          .get
        val results: Seq[ScalafixResult] = index.database.documents.map { d =>
          val filename = RelativePath(d.input.syntax)
          val tree = {
            import scala.meta._
            d.input.parse[m.Source].get
          }
          val ctx = RuleCtx(tree, config)
          val patches = rule.fixWithName(ctx)
          val diagnostics = for {
            (name, patch) <- patches.toIterator
            msg <- Patch.lintMessages(patch)
          } yield toDiagnostic(name, msg)
          ScalafixResult(filename, diagnostics.toSeq)
        }
        // megaCache needs to die, if we forget this we will read stale
        // snapshots of filenames if using m.Input.File.slurp
        // https://github.com/scalameta/scalameta/issues/1068
        PlatformTokenizerCache.megaCache.clear()
        if (results.isEmpty) {
          connection.showMessage(MessageType.Warning,
                                 "Ran scalafix but found no lint messages :(")
        }
        results
    }

  def toSeverity(s: LintSeverity): Int = s match {
    case LintSeverity.Error   => l.DiagnosticSeverity.Error
    case LintSeverity.Warning => l.DiagnosticSeverity.Warning
    case LintSeverity.Info    => l.DiagnosticSeverity.Information
  }

  def toRange(pos: m.Position): l.Range = l.Range(
    l.Position(line = pos.startLine, character = pos.startColumn),
    l.Position(line = pos.endLine, character = pos.endColumn)
  )

  def toDiagnostic(name: RuleName, msg: LintMessage): l.Diagnostic = {
    l.Diagnostic(
      range = toRange(msg.position),
      severity = Some(toSeverity(msg.category.severity)), // TODO(olafur) ignored configuration severity
      code = Some(
        // TODO(olafur) remove once scalafix 0.5.4 is out with LintMessage.id(RuleName).
        if (msg.category.id.isEmpty) name.value
        else s"${name.value}.${msg.category.id}"
      ),
      source = Some("scalafix"),
      message = msg.message
    )
  }
}
