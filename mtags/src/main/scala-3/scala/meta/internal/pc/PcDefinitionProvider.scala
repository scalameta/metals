package scala.meta.internal.pc

import java.nio.file.Paths
import java.util.ArrayList

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Denotations.MultiPreDenotation
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types.NamedType
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.Location

class PcDefinitionProvider(
    driver: InteractiveDriver,
    params: OffsetParams,
    search: SymbolSearch
) {

  def definitions(): DefinitionResult = {
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
  }

  private def findDefinitions(
      tree: Tree,
      path: List[Tree],
      pos: SourcePosition,
      driver: InteractiveDriver,
      indexed: IndexedContext
  ): DefinitionResult = {
    import indexed.ctx
    enclosingSymbols(path, pos, indexed) match {
      case symbols @ (sym :: other) =>
        val isLocal = sym.source == pos.source
        if (isLocal)
          val defs =
            Interactive.findDefinitions(List(sym), driver, false, false)
          defs.headOption match {
            case Some(srcTree) =>
              val pos = srcTree.namePos
              pos.toLocation match {
                case None => DefinitionResultImpl.empty
                case Some(loc) =>
                  DefinitionResultImpl(
                    SemanticdbSymbols.symbolName(sym),
                    List(loc).asJava
                  )
              }
            case None =>
              DefinitionResultImpl.empty
          }
        else {
          val res = new ArrayList[Location]()
          semanticSymbolsSorted(symbols)
            .foreach { sym =>
              res.addAll(search.definition(sym, params.uri()))
            }
          DefinitionResultImpl(
            SemanticdbSymbols.symbolName(sym),
            res
          )
        }
      case Nil => DefinitionResultImpl.empty
    }
  }

  @tailrec
  private def enclosingSymbols(
      path: List[Tree],
      pos: SourcePosition,
      indexed: IndexedContext
  ): List[Symbol] = {
    import indexed.ctx
    path match {
      // For a named arg, find the target `DefDef` and jump to the param
      case NamedArg(name, _) :: Apply(fn, _) :: _ =>
        val funSym = fn.symbol
        if (
          funSym.name == StdNames.nme.copy
          && funSym.is(Synthetic)
          && funSym.owner.is(CaseClass)
        ) List(funSym.owner.info.member(name).symbol)
        else {
          val classTree = funSym.topLevelClass.asClass.rootTree
          val paramSymbol =
            for {
              DefDef(_, paramss, _, _) <- tpd
                .defPath(funSym, classTree)
                .lastOption
              param <- paramss.flatten.find(_.name == name)
            } yield param.symbol
          List(paramSymbol.getOrElse(fn.symbol))
        }
      case (_: untpd.ImportSelector) :: (imp: Import) :: _ =>
        importedSymbols(imp, _.span.contains(pos.span))

      case (imp: Import) :: _ =>
        importedSymbols(imp, _.span.contains(pos.span))

      // For constructor calls, return the `<init>` that was selected
      case _ :: (_: New) :: (select: Select) :: _ =>
        List(select.symbol)

      // wildcard param
      case head :: _ if (head.symbol.is(Param) && head.symbol.is(Synthetic)) =>
        Nil

      case head :: tl =>
        if (head.symbol.is(Synthetic)) enclosingSymbols(tl, pos, indexed)
        else if (head.symbol != NoSymbol) List(head.symbol)
        else {
          val recovered = recoverError(head, indexed)
          if (recovered.isEmpty) enclosingSymbols(tl, pos, indexed)
          else recovered
        }
      case Nil => Nil
    }
  }

  private def recoverError(
      tree: Tree,
      indexed: IndexedContext
  ): List[Symbol] = {
    import indexed.ctx

    def recoverDenot(d: Denotation): List[Symbol] = d match {
      case multi: MultiPreDenotation =>
        List(multi.last, multi.first)
          .map(_.symbol)
          .filter(_ != NoSymbol)
      case _ => List(d.symbol).filter(_ != NoSymbol)
    }

    tree match {
      case select: Select =>
        recoverDenot(select.qualifier.typeOpt.member(select.name))
      case ident: Ident => indexed.findSymbol(ident.name).toList.flatten
      case _ => Nil
    }
  }

  def semanticSymbolsSorted(
      syms: List[Symbol]
  )(using ctx: Context): List[String] = {
    syms
      .map { sym =>
        // in case of having the same type and teerm symbol
        // term comes first
        // used only for ordering symbols that come from `Import`
        val termFlag =
          if (sym.is(ModuleClass)) sym.sourceModule.isTerm
          else sym.isTerm
        (termFlag, SemanticdbSymbols.symbolName(sym))
      }
      .sorted
      .map(_._2)
  }

}
