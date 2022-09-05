package scala.meta.internal.pc

import java.{util => ju}

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Location

class PcDefinitionProvider(val compiler: MetalsGlobal, params: OffsetParams) {
  import compiler._
  def definition(): DefinitionResult = {
    if (params.isWhitespace || params.isDelimiter || params.offset() == 0) {
      DefinitionResultImpl.empty
    } else {
      val unit = addCompilationUnit(
        params.text(),
        params.uri().toString(),
        None
      )
      typeCheck(unit)
      val pos = unit.position(params.offset())
      val tree = definitionTypedTreeAt(pos)
      if (
        tree.symbol == null ||
        tree.symbol == NoSymbol ||
        tree.symbol.isErroneous
      ) {
        DefinitionResultImpl.empty
      } else if (tree.symbol.hasPackageFlag) {
        DefinitionResultImpl(
          semanticdbSymbol(tree.symbol),
          ju.Collections.emptyList()
        )
      } else if (
        tree.symbol.pos != null &&
        tree.symbol.pos.isDefined &&
        tree.symbol.pos.source.eq(unit.source)
      ) {
        DefinitionResultImpl(
          semanticdbSymbol(tree.symbol),
          ju.Collections.singletonList(
            new Location(params.uri().toString(), tree.symbol.pos.focus.toLsp)
          )
        )
      } else {
        val res = new ju.ArrayList[Location]()
        tree.symbol.alternatives
          .map(semanticdbSymbol)
          .sorted
          .foreach { sym =>
            if (sym.isGlobal) {
              res.addAll(search.definition(sym, params.uri()))
            }
          }
        DefinitionResultImpl(
          semanticdbSymbol(tree.symbol),
          res
        )
      }
    }
  }

  def definitionTypedTreeAt(pos: Position): Tree = {
    def loop(tree: Tree): Tree = {
      tree match {
        case Select(qualifier, name) =>
          if (
            name == termNames.apply &&
            qualifier.pos.includes(pos)
          ) {
            if (definitions.isFunctionSymbol(tree.symbol.owner)) loop(qualifier)
            else if (definitions.isTupleSymbol(tree.symbol.owner)) {
              EmptyTree
            } else {
              tree
            }
          } else {
            tree
          }
        case Apply(sel @ Select(New(_), termNames.CONSTRUCTOR), _)
            if sel.pos != null &&
              sel.pos.isRange &&
              !sel.pos.includes(pos) =>
          // Position is on `new` keyword
          EmptyTree
        case TreeApply(fun, _)
            if fun.pos != null &&
              fun.pos.isDefined &&
              fun.pos.point == tree.pos.point &&
              definitions.isTupleSymbol(tree.symbol.owner.companionClass) =>
          EmptyTree
        case _ => tree
      }
    }
    val typedTree = locateTree(pos)
    val tree0 = typedTree match {
      case sel @ Select(qual, _) if sel.tpe == ErrorType => qual
      case Import(expr, _) => expr
      case t => t
    }
    val context = doLocateContext(pos)
    val shouldTypeQualifier = tree0.tpe match {
      case null => true
      case mt: MethodType => mt.isImplicit
      case pt: PolyType => isImplicitMethodType(pt.resultType)
      case _ => false
    }
    // TODO: guard with try/catch to deal with ill-typed qualifiers.
    val tree =
      if (shouldTypeQualifier) analyzer.newTyper(context).typedQualifier(tree0)
      else tree0
    typedTree match {
      case i @ Import(expr, _) =>
        if (expr.pos.includes(pos)) {
          expr.findSubtree(pos)
        } else {
          i.selector(pos) match {
            case None => EmptyTree
            case Some(sym) => Ident(sym.name).setSymbol(sym)
          }
        }
      case sel @ Select(_, name) if sel.tpe == ErrorType =>
        val symbols = tree.tpe.member(name)
        typedTree.setSymbol(symbols)
      case defn: DefTree if !defn.namePos.metalsIncludes(pos) =>
        EmptyTree
      case _: Template =>
        EmptyTree
      case _ =>
        loop(tree)
    }
  }

}
