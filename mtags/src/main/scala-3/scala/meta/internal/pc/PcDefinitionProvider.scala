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

import com.fasterxml.jackson.databind.util.Named
import dotty.tools.dotc.ast.Trees.NamedDefTree
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Denotations.MultiPreDenotation
import dotty.tools.dotc.core.Denotations.PreDenotation
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types.NamedType
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.Interactive.Include
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.NoSourcePosition
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.Location

class PcDefinitionProvider(
    driver: MetalsDriver,
    params: OffsetParams,
    search: SymbolSearch
) {

  def definitions(): DefinitionResult = {
    val uri = params.uri
    val filePath = Paths.get(uri)
    val result = driver.run(
      uri,
      SourceFile.virtual(filePath.toString, params.text)
    )

    val pos = result.positionOf(params.offset)
    val path =
      Interactive.pathTo(result.tree, pos.span)(using result.context)
    given ctx: Context =
      MetalsInteractive.contextOfPath(path)(using result.context)
    val indexedContext = IndexedContext(ctx)
    findDefinitions(result.tree, path, pos, indexedContext)
  }

  private def findDefinitions(
      tree: Tree,
      path: List[Tree],
      pos: SourcePosition,
      indexed: IndexedContext
  ): DefinitionResult = {
    import indexed.ctx
    enclosingSymbols(path, pos, indexed) match {
      case symbols @ (sym :: other) =>
        val isLocal = sym.source == pos.source
        if (isLocal)
          findLocal(tree, sym.name) match {
            case Some((sym, pos)) =>
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

  private def findLocal(tree: Tree, name: Name)(using
      Context
  ): Option[(Symbol, SourcePosition)] = {
    var result = Option.empty[(Symbol, SourcePosition)]
    val traverser = new TreeTraverser {

      override def traverse(tree: Tree)(using Context): Unit = {
        tree match {
          case named: tpd.NamedDefTree
              if named.name == name && result.isEmpty =>
            result = Some(named.symbol, named.namePos)
          case _ if result.isEmpty =>
            traverseChildren(tree)
          case _ =>
        }
      }
    }
    traverser.traverse(tree)
    result
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
        if (funSym.is(Synthetic) && funSym.owner.is(CaseClass))
          List(funSym.owner.info.member(name).symbol)
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

    def extractSymbols(d: PreDenotation): List[Symbol] = {
      d match {
        case multi: MultiPreDenotation =>
          extractSymbols(multi.denot1) ++ extractSymbols(multi.denot2)
        case d: Denotation => List(d.symbol)
        case _ => List.empty
      }
    }

    tree match {
      case select: Select =>
        extractSymbols(select.qualifier.typeOpt.member(select.name))
          .filter(_ != NoSymbol)
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
