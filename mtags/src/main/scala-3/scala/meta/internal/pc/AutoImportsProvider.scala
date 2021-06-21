package scala.meta.internal.pc

import java.nio.file.Paths
import java.{util => ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.AutoImports._
import scala.meta.pc._

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j => l}

final class AutoImportsProvider(
    search: SymbolSearch,
    driver: InteractiveDriver,
    name: String,
    params: OffsetParams,
    config: PresentationCompilerConfig
) {

  def autoImports(): List[AutoImportsResult] = {
    val uri = params.uri
    val filePath = Paths.get(uri)
    driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text)
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
    def visit(sym: Symbol): Boolean = {
      val name = sym.denot.fullName.show
      if (!isSeen(name)) {
        isSeen += name
        symbols += sym
        true
      } else false
    }
    def isExactMatch(sym: Symbol, query: String): Boolean = {
      sym.name.show == query
    }

    val visitor = new CompilerSearchVisitor(name, visit)
    search.search(name, "", visitor)
    val results = symbols.result.filter(isExactMatch(_, name))

    if (results.nonEmpty) {
      val correctedPos = CompletionPos.infer(pos, params.text, path).sourcePos
      val generator =
        AutoImports.generator(
          correctedPos,
          params.text,
          tree,
          indexedContext.importContext,
          config
        )

      for {
        sym <- results
        edits <- generator.forSymbol(sym)
      } yield AutoImportsResultImpl(
        sym.owner.showFullName,
        edits.asJava
      )

    } else {
      List.empty
    }
  }

}
