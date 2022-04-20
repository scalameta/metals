package scala.meta.internal.pc

import java.nio.file.Paths
import java.util.ArrayList

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.ModuleClass
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.Location

class PcDefinitionProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    search: SymbolSearch
):

  def definitions(): DefinitionResult =
    val uri = params.uri
    val filePath = Paths.get(uri)
    val diagnostics = driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text)
    )
    val unit = driver.currentCtx.run.units.head
    val tree = unit.tpdTree

    val pos = driver.sourcePosition(params)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
    given ctx: Context = driver.localContext(params)
    val indexedContext = IndexedContext(ctx)
    findDefinitions(tree, path, pos, driver, indexedContext)
  end definitions

  private def findDefinitions(
      tree: Tree,
      path: List[Tree],
      pos: SourcePosition,
      driver: InteractiveDriver,
      indexed: IndexedContext
  ): DefinitionResult =
    import indexed.ctx
    MetalsInteractive.enclosingSymbols(path, pos, indexed) match
      case symbols @ (sym :: other) =>
        val isLocal = sym.source == pos.source
        if isLocal then
          val defs =
            Interactive.findDefinitions(List(sym), driver, false, false)
          defs.headOption match
            case Some(srcTree) =>
              val pos = srcTree.namePos
              pos.toLocation match
                case None => DefinitionResultImpl.empty
                case Some(loc) =>
                  DefinitionResultImpl(
                    SemanticdbSymbols.symbolName(sym),
                    List(loc).asJava
                  )
            case None =>
              DefinitionResultImpl.empty
        else
          val res = new ArrayList[Location]()
          semanticSymbolsSorted(symbols)
            .foreach { sym =>
              res.addAll(search.definition(sym, params.uri()))
            }
          DefinitionResultImpl(
            SemanticdbSymbols.symbolName(sym),
            res
          )
        end if
      case Nil => DefinitionResultImpl.empty
    end match
  end findDefinitions

  def semanticSymbolsSorted(
      syms: List[Symbol]
  )(using ctx: Context): List[String] =
    syms
      .map { sym =>
        // in case of having the same type and teerm symbol
        // term comes first
        // used only for ordering symbols that come from `Import`
        val termFlag =
          if sym.is(ModuleClass) then sym.sourceModule.isTerm
          else sym.isTerm
        (termFlag, SemanticdbSymbols.symbolName(sym))
      }
      .sorted
      .map(_._2)

end PcDefinitionProvider
