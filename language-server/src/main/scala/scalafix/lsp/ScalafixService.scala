// In scalafix package to access private[scalafix] methods.
package scalafix.lsp

import java.nio.file.Files
import java.nio.file.Path
import scala.compat.Platform
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.tools.nsc.interpreter.OutputStream
import scala.{meta => m}
import scalafix._
import scalafix.internal.config.LazySemanticdbIndex
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.reflect.ScalafixCompilerDecoder
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.lint.LintSeverity
import scalafix.patch.Patch
import scalafix.rule.RuleCtx
import scalafix.rule.RuleName
import scalafix.util.SemanticdbIndex
import langserver.{types => l}
import metaconfig.ConfDecoder
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath

class ScalafixService(cwd: AbsolutePath, out: OutputStream) {
  def this(path: Path, out: OutputStream) = this(AbsolutePath(path), out)
  val configFile: Option[m.Input] = ScalafixConfig.auto(cwd)
  def onNewSemanticdb(path: Path): Seq[l.Diagnostic] =
    onNewSemanticdb(s.Database.parseFrom(Files.readAllBytes(path)))
  def onNewSemanticdb(database: s.Database): Seq[l.Diagnostic] =
    onNewSemanticdb(database.toDb(None))
  def onNewSemanticdb(database: m.Database): Seq[l.Diagnostic] = {
    onNewSemanticdb(
      EagerInMemorySemanticdbIndex(database,
                                   m.Sourcepath(Nil),
                                   m.Classpath(Nil)))
  }

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(filename: String,
                       contents: String): Seq[l.Diagnostic] = {
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
  def onNewSemanticdb(index: SemanticdbIndex): Seq[l.Diagnostic] = {
    configFile match {
      case None =>
        sys.error(s"Missing .scalafix.conf in working directory $cwd")
      case Some(configInput) =>
        val lazyIndex = LazySemanticdbIndex(_ => Some(index))
        val (rule, config) = ScalafixConfig
          .fromInput(
            configInput,
            lazyIndex,
            extraRules = Nil // Can pass in List("RemoveUnusedImports")
          )(ScalafixCompilerDecoder.baseCompilerDecoder(lazyIndex))
          .get
        val results = for {
          d <- index.database.documents
          tree = {
            import scala.meta._
            d.input.parse[m.Source].get
          }
          ctx = RuleCtx(tree)
          patches = rule.fixWithName(ctx)
          (name, patch) <- patches
          msg <- Patch.lintMessages(patch)
        } yield toDiagnostic(name, msg)
        // megaCache needs to die, if we forget this we will read stale
        // snapshots of filenames if using m.Input.File.slurp
        // https://github.com/scalameta/scalameta/issues/1068
        PlatformTokenizerCache.megaCache.clear()
        results
    }
  }

  def toSeverity(s: LintSeverity): Int = {
    import LintSeverity._
    s match {
      case Error   => l.DiagnosticSeverity.Error
      case Warning => l.DiagnosticSeverity.Warning
      case Info    => l.DiagnosticSeverity.Information
    }
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
      source = Some("scala"),
      message = msg.message
    )
  }
}
