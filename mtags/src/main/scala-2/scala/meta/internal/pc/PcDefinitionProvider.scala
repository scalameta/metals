package scala.meta.internal.pc

import java.{util => ju}

import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.Scala._
import scala.meta.pc.DefinitionResult
import scala.meta.pc.OffsetParams

import org.eclipse.lsp4j.Location

class PcDefinitionProvider(val compiler: MetalsGlobal, params: OffsetParams) {
  import compiler._

  def definition(): DefinitionResult =
    definition(findTypeDef = false)

  def typeDefinition(): DefinitionResult =
    definition(findTypeDef = true)

  private def typeSymbol(tree: Tree, pos: Position) = {
    val expanded = expandRangeToEnclosingApply(pos)
    if (!tree.isEmpty && expanded.symbol != null) {
      val tpe =
        if (expanded.tpe != NoType && expanded.tpe != null) {
          namedParamSymbol(expanded, pos).map(_.tpe).getOrElse(expanded.tpe)
        } else expanded.symbol.tpe
      // typeSymbol also dealiases, which we don't want to do
      val tpeSym = tpe.widen.finalResultType.typeSymbolDirect
      if (tpeSym.isTypeParameter)
        seenFromType(expanded, tpeSym).typeSymbol
      else if (tpeSym != NoSymbol) tpeSym
      else tree.symbol
    } else
      expanded match {
        case _: Literal => expanded.tpe.widen.typeSymbol
        case _ => tree.symbol
      }

  }

  /**
   * Handle named parameters, which are lost in typed trees.
   */
  private def namedParamSymbol(tree: Tree, pos: Position): Option[Symbol] = {
    tree match {
      case TreeApply(fun, _) if !fun.pos.includes(pos) =>
        locateUntyped(pos) match {
          case Ident(name) =>
            tree.symbol.tpe.params.find(_.name == name)
          case _ => None
        }
      case _ => None
    }
  }

  private def definition(findTypeDef: Boolean): DefinitionResult = {
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
      val symbol =
        if (findTypeDef) typeSymbol(tree, pos)
        else namedParamSymbol(tree, pos).getOrElse(tree.symbol)

      if (
        symbol == null ||
        symbol == NoSymbol ||
        symbol.isError
      ) {
        DefinitionResultImpl.empty
      } else if (symbol.hasPackageFlag) {
        DefinitionResultImpl(
          semanticdbSymbol(symbol),
          ju.Collections.emptyList()
        )
      } else {
        val allSyms = tree match {
          case Select(qual, nme.apply)
              if !findTypeDef
                && qual.pos.sameRange(tree.pos)
                && qual.hasExistingSymbol =>
            if (symbol.isCaseApplyOrUnapply) // no symbol to navigate to
              List(symbol.owner.companionClass)
            else
              List(symbol, qual.symbol)
          case _ =>
            List(symbol)
        }
        DefinitionResultImpl(
          semanticdbSymbol(symbol),
          allSyms.flatMap(findSymbolLocations(_, unit)).asJava
        )
      }
    }
  }

  private def findSymbolLocations(
      symbol: Symbol,
      unit: CompilationUnit
  ): List[Location] = {
    if (
      symbol.pos != null &&
      symbol.pos.isDefined &&
      symbol.pos.source.eq(unit.source)
    ) {
      val focused = symbol.pos.focus
      val actualName = symbol.decodedName.stripSuffix("_=").trim
      val namePos =
        if (symbol.name.startsWith("x$") && symbol.isSynthetic) focused
        else focused.withEnd(focused.start + actualName.length())
      val adjusted = namePos.adjust(unit.source.content)._1
      List(new Location(params.uri().toString(), adjusted.toLsp))

    } else {
      symbol.alternatives
        .map(semanticdbSymbol)
        .sorted
        .flatMap { sym =>
          if (sym.isGlobal) {
            search.definition(sym, params.uri()).asScala
          } else Nil
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
      case defn: DefTree if !defn.namePosition.metalsIncludes(pos) =>
        EmptyTree
      case _: Template =>
        EmptyTree
      case _ =>
        loop(tree)
    }
  }

}
