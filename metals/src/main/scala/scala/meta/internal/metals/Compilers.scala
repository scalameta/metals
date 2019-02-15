package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaBuildTarget
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.TextDocumentPositionParams
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import scala.collection.concurrent.TrieMap
import scala.concurrent.Promise
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.pc.ScalaPresentationCompiler
import scala.meta.pc.PresentationCompiler
import scala.meta.pc.SymbolIndexer
import scala.meta.pc.SymbolSearch
import scala.tools.nsc.Properties

class Compilers(
    buildTargets: BuildTargets,
    buffers: Buffers,
    indexer: SymbolIndexer,
    search: SymbolSearch,
    embedded: Embedded,
    statusBar: StatusBar
) extends Cancelable {

  private val cache = TrieMap.empty[BuildTargetIdentifier, PresentationCompiler]
  override def cancel(): Unit = {
    Cancelable.cancelEach(cache.values)(_.shutdown())
    cache.clear()
  }
  def didCompileSuccessfully(id: BuildTargetIdentifier): Unit = {
    cache.remove(id).foreach(_.shutdown())
  }

  def completionItemResolve(
      item: CompletionItem,
      token: CancelChecker
  ): Option[CompletionItem] = {
    for {
      data <- item.data
      compiler <- cache.get(new BuildTargetIdentifier(data.target))
    } yield compiler.completionItemResolve(item, data.symbol)
  }
  def completions(
      params: CompletionParams,
      token: CancelChecker
  ): Option[CompletionList] =
    withPC(params) { (pc, pos) =>
      pc.complete(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }
  def hover(
      params: TextDocumentPositionParams,
      token: CancelChecker
  ): Option[Hover] =
    withPC(params) { (pc, pos) =>
      pc.hover(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }
  def signatureHelp(
      params: TextDocumentPositionParams,
      token: CancelChecker
  ): Option[SignatureHelp] =
    withPC(params) { (pc, pos) =>
      pc.signatureHelp(
        CompilerOffsetParams(pos.input.syntax, pos.input.text, pos.start, token)
      )
    }

  private def withPC[T](
      params: TextDocumentPositionParams
  )(fn: (PresentationCompiler, Position) => T): Option[T] = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    for {
      target <- buildTargets.inverseSources(path)
      info <- buildTargets.info(target)
      scala <- info.asScalaBuildTarget
      isSupported = ScalaVersions.isSupportedScalaVersion(scala.getScalaVersion)
      _ = {
        if (!isSupported) {
          scribe.warn(s"unsupported Scala ${scala.getScalaVersion}")
        }
      }
      if isSupported
      scalac <- buildTargets.scalacOptions(target)
    } yield {
      val promise = Promise[Unit]()
      try {
        val compiler = cache.getOrElseUpdate(
          target, {
            statusBar.trackFuture(
              s"${statusBar.icons.sync}Loading presentation compiler",
              promise.future
            )
            newCompiler(scalac, scala)
          }
        )
        val input = path.toInputFromBuffers(buffers)
        val pos = params.getPosition.toMeta(input)
        val result = fn(compiler, pos)
        result
      } finally {
        promise.trySuccess(())
      }
    }
  }

  def newCompiler(
      scalac: ScalacOptionsItem,
      info: ScalaBuildTarget
  ): PresentationCompiler = {
    val classpath = scalac.classpath.map(_.toNIO).toSeq
    val pc: PresentationCompiler =
      if (info.getScalaVersion == Properties.versionNumberString) {
        new ScalaPresentationCompiler()
      } else {
        embedded.presentationCompiler(info, scalac)
      }
    pc.withIndexer(indexer)
      .withSearch(search)
      .newInstance(
        scalac.getTarget.getUri,
        classpath.asJava,
        scalac.getOptions
      )
  }
}
