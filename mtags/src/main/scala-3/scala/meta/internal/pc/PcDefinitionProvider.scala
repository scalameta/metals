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

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Denotations.MultiPreDenotation
import dotty.tools.dotc.core.Denotations.PreDenotation
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.NamedType
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.transform.SymUtils.*
import dotty.tools.dotc.util.NoSourcePosition
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
    enclosingSymbols(path, pos, indexed) match
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

  @tailrec
  private def enclosingSymbols(
      path: List[Tree],
      pos: SourcePosition,
      indexed: IndexedContext
  ): List[Symbol] =
    import indexed.ctx
    path match
      // For a named arg, find the target `DefDef` and jump to the param
      case NamedArg(name, _) :: Apply(fn, _) :: _ =>
        val funSym = fn.symbol
        if funSym.is(Synthetic) && funSym.owner.is(CaseClass) then
          List(funSym.owner.info.member(name).symbol)
        else
          val classTree = funSym.topLevelClass.asClass.rootTree
          val paramSymbol =
            for
              DefDef(_, paramss, _, _) <- tpd
                .defPath(funSym, classTree)
                .lastOption
              param <- paramss.flatten.find(_.name == name)
            yield param.symbol
          List(paramSymbol.getOrElse(fn.symbol))
      case (_: untpd.ImportSelector) :: (imp: Import) :: _ =>
        importedSymbols(imp, _.span.contains(pos.span))

      case (imp: Import) :: _ =>
        importedSymbols(imp, _.span.contains(pos.span))

      // wildcard param
      case head :: _ if (head.symbol.is(Param) && head.symbol.is(Synthetic)) =>
        List(head.symbol)

      case (head @ Select(target, name)) :: _
          if head.symbol.is(Synthetic) && name == StdNames.nme.apply =>
        val sym = target.symbol
        if sym.is(Synthetic) && sym.is(Module) then List(sym.companionClass)
        else List(target.symbol)

      case path @ head :: tl =>
        if head.symbol.is(Synthetic) then enclosingSymbols(tl, pos, indexed)
        else if head.symbol != NoSymbol then
          if MetalsInteractive.isOnName(
              path,
              pos,
              indexed.ctx.source
            ) || MetalsInteractive.isForSynthetic(head)
          then List(head.symbol)
          else Nil
        else
          val recovered = recoverError(head, indexed)
          if recovered.isEmpty then enclosingSymbols(tl, pos, indexed)
          else recovered
      case Nil => Nil
    end match
  end enclosingSymbols

  private def recoverError(
      tree: Tree,
      indexed: IndexedContext
  ): List[Symbol] =
    import indexed.ctx

    def extractSymbols(d: PreDenotation): List[Symbol] =
      d match
        case multi: MultiPreDenotation =>
          extractSymbols(multi.denot1) ++ extractSymbols(multi.denot2)
        case d: Denotation => List(d.symbol)
        case _ => List.empty

    tree match
      case select: Select =>
        extractSymbols(select.qualifier.typeOpt.member(select.name))
          .filter(_ != NoSymbol)
      case ident: Ident => indexed.findSymbol(ident.name).toList.flatten
      case _ => Nil
  end recoverError

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
