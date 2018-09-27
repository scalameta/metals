package scala.meta.metals

import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.lsp.Diagnostic
import scala.meta.parsers.Parsed
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
import org.langmeta.io.AbsolutePath
import org.langmeta.inputs.Input
import scala.meta.jsonrpc.JsonRpcClient
import scala.meta.jsonrpc.Response
import scala.meta.lsp.Window.showMessage
import cats.syntax.bifunctor._
import cats.instances.either._
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.eval.Task
import scala.meta.jsonrpc.MonixEnrichments._
import java.nio.file.Files

class Linter(configuration: Observable[Configuration], cwd: AbsolutePath)(
    implicit client: JsonRpcClient,
    s: Scheduler
) extends MetalsLogger {

  // Simple method to run syntactic scalafix rules on a string.
  def onSyntacticInput(
      filename: String,
      contents: String
  ): Task[Either[Response.Error, Seq[Diagnostic]]] = {
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

  def linterMessages(
      mdoc: m.Document
  ): Task[Either[Response.Error, Seq[Diagnostic]]] =
    analyzeIndex(
      mdoc,
      EagerInMemorySemanticdbIndex(
        m.Database(mdoc :: Nil),
        m.Sourcepath(Nil),
        m.Classpath(Nil)
      )
    )

  private val config: () => Either[String, Option[Input]] =
    configuration
      .focus(_.scalafix.confPath)
      .map[Either[String, Option[Input]]] {
        case None =>
          val default = cwd.resolve(Configuration.Scalafix.defaultConfPath)
          if (Files.isRegularFile(default.toNIO))
            Right(Some(Input.File(default)))
          else Right(None)
        case Some(relpath) =>
          val custom = cwd.resolve(relpath)
          if (Files.isRegularFile(custom.toNIO))
            Right(Some(Input.File(custom)))
          else if (relpath == Configuration.Scalafix.defaultConfPath)
            Right(None)
          else
            Left(s"metals.scalafix.confPath=$relpath is not a file")
      }
      .toFunction0()

  private def analyzeIndex(
      document: m.Document,
      index: SemanticdbIndex
  ): Task[Either[Response.Error, Seq[Diagnostic]]] = Task {
    val linterResult = for {
      scalafixConfig <- config()
    } yield
      scalafixConfig match {
        case None => Nil // no scalafix config, nothing to do
        case Some(configInput) =>
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
    linterResult.leftMap { message =>
      // We show a message here to be sure the message is
      // reported in the UI. invalidParams responses don't
      // get reported in vscode at least.
      showMessage.error(message)
      Response.invalidParams(message)
    }
  }

  private def lazySemanticdbIndex(index: SemanticdbIndex): LazySemanticdbIndex =
    new LazySemanticdbIndex(_ => Some(index), ScalafixReporter.default)

}
