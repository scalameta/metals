package scala.meta.languageserver

import java.io.PrintStream
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.parsers.Parsed
import scala.tools.nsc.interpreter.OutputStream
import scala.{meta => m}
import scalafix.internal.config.LazySemanticdbIndex
import scalafix.internal.config.ScalafixConfig
import scalafix.internal.config.ScalafixReporter
import scalafix.internal.util.EagerInMemorySemanticdbIndex
import scalafix.languageserver.ScalafixEnrichments._
import scalafix.patch.Patch
import scalafix.reflect.ScalafixReflect
import scalafix.rule.RuleCtx
import scalafix.util.SemanticdbIndex
import com.typesafe.scalalogging.LazyLogging
import langserver.types.Diagnostic
import org.langmeta.io.AbsolutePath

class Linter(cwd: AbsolutePath) extends LazyLogging {

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(
      filename: String,
      contents: String
  ): Seq[Diagnostic] = {
    val mdoc = m.Document(
      m.Input.VirtualFile(filename, contents),
      "scala212",
      Nil,
      Nil,
      Nil,
      Nil
    )
    analyzeIndex(
      mdoc,
      EagerInMemorySemanticdbIndex(
        m.Database(mdoc :: Nil),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
    )
  }

  def linterMessages(mdoc: m.Document): Seq[Diagnostic] =
    analyzeIndex(
      mdoc,
      EagerInMemorySemanticdbIndex(
        m.Database(mdoc :: Nil),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
    )

  private def analyzeIndex(
      document: m.Document,
      index: SemanticdbIndex
  ): Seq[Diagnostic] =
    withConfig { configInput =>
      val lazyIndex = lazySemanticdbIndex(index)
      val configDecoder = ScalafixReflect.fromLazySemanticdbIndex(lazyIndex)
      val (rule, config) =
        ScalafixConfig.fromInput(configInput, lazyIndex)(configDecoder).get
      val results: Seq[Diagnostic] = Parser.parse(document.input) match {
        case Parsed.Error(_, _, _) => Nil
        case Parsed.Success(tree) =>
          val ctx = RuleCtx.applyInternal(tree, config)
          val patches = rule.fixWithNameInternal(ctx)
          Patch.lintMessagesInternal(patches, ctx).map(_.toLSP)
      }

      // megaCache needs to die, if we forget this we will read stale
      // snapshots of filenames if using m.Input.File.slurp
      // https://github.com/scalameta/scalameta/issues/1068
      PlatformTokenizerCache.megaCache.clear()

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
    new LazySemanticdbIndex(_ => Some(index), ScalafixReporter.default)

}
