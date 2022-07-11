package scala.meta.internal.pc

import java.nio.file.Paths
import java.{util as ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.*
import scala.meta.internal.pc.completions.CompletionPos
import scala.meta.pc.*

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Decorators.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j as l}

final class AutoImportsProvider(
    search: SymbolSearch,
    driver: InteractiveDriver,
    name: String,
    params: OffsetParams,
    config: PresentationCompilerConfig,
    buildTargetIdentifier: String,
):

  def autoImports(): List[AutoImportsResult] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text),
    )
    val unit = driver.currentCtx.run.units.head
    val tree = unit.tpdTree

    val pos = driver.sourcePosition(params)

    val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
    val path =
      Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using newctx)

    val indexedContext = IndexedContext(
      MetalsInteractive.contextOfPath(path)(using newctx)
    )
    import indexedContext.ctx

    val isSeen = mutable.Set.empty[String]
    val symbols = List.newBuilder[Symbol]
    def visit(sym: Symbol): Boolean =
      val name = sym.denot.fullName.show
      if !isSeen(name) then
        isSeen += name
        symbols += sym
        true
      else false
    def isExactMatch(sym: Symbol, query: String): Boolean =
      sym.name.show == query

    val visitor = new CompilerSearchVisitor(name, visit)
    search.search(name, buildTargetIdentifier, visitor)
    val results = symbols.result.filter(isExactMatch(_, name))

    if results.nonEmpty then
      val correctedPos = CompletionPos.infer(pos, params.text, path).sourcePos
      val generator =
        AutoImports.generator(
          correctedPos,
          params.text,
          tree,
          indexedContext.importContext,
          config,
        )

      for
        sym <- results
        edits <- generator.forSymbol(sym)
      yield AutoImportsResultImpl(
        sym.owner.showFullName,
        edits.asJava,
      )
    else List.empty
    end if
  end autoImports

end AutoImportsProvider
