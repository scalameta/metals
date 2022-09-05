package scala.meta.internal.pc

import scala.reflect.internal.util.Position
import scala.reflect.internal.{Flags => gf}
import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind

class HoverProvider(val compiler: MetalsGlobal, params: OffsetParams) {
  import compiler._

  def hover(): Option[Hover] = params match {
    case range: RangeParams =>
      range.trimWhitespaceInRange.flatMap(hoverOffset)
    case _ if params.isWhitespace => None
    case _ => hoverOffset(params)
  }

  def hoverOffset(params: OffsetParams): Option[Hover] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )
    val (pos, tree) = params match {
      case params: RangeParams =>
        val pos = Position.range(
          unit.source,
          params.offset(),
          params.offset(),
          params.endOffset()
        )
        val tree = typedHoverTreeAt(pos, unit)
        (pos, tree)
      case params: OffsetParams =>
        val pos = unit.position(params.offset())
        val tree = typedHoverTreeAt(pos, unit)
        (pos, tree)
    }
    tree match {
      case i @ Import(_, _) =>
        for {
          member <- i.selector(pos)
          hover <- toHover(member, pos)
        } yield hover
      case _: Select | _: Apply | _: TypeApply | _: Ident =>
        val expanded = expandRangeToEnclosingApply(pos)
        if (
          expanded != null &&
          expanded.tpe != null &&
          tree.symbol != null &&
          expanded.symbol != null
        ) {
          val symbol =
            if (expanded.symbol.isConstructor) expanded.symbol
            else tree.symbol
          toHover(
            symbol,
            symbol.keyString,
            seenFromType(tree, symbol),
            expanded.tpe,
            pos,
            expanded.pos
          )
        } else {
          for {
            sym <- Option(tree.symbol)
            tpe <- Option(tree.tpe)
            seenFrom = seenFromType(tree, sym)
            hover <- toHover(sym, sym.keyString, seenFrom, tpe, pos, tree.pos)
          } yield hover
        }
      case UnApply(fun, _) if fun.symbol != null =>
        toHover(
          fun.symbol,
          fun.symbol.keyString,
          seenFromType(tree, fun.symbol),
          tree.tpe,
          pos,
          pos
        )
      // Def, val or val definition, example `val x: Int = 1`
      // Matches only if the cursor is over the definition name.
      case v: ValOrDefDef
          if (v.namePos
            .includes(pos) || pos.includes(v.namePos)) && v.symbol != null =>
        val symbol = (v.symbol.getter: Symbol) match {
          case NoSymbol => v.symbol
          case getter => getter
        }
        toHover(
          symbol,
          v.symbol.keyString,
          symbol.info,
          symbol.info,
          pos,
          v.pos
        )
      // Bound variable in a pattern match, example `head` in `case head :: tail =>`
      case _: Bind =>
        val symbol = tree.symbol
        toHover(
          symbol = symbol,
          keyword = "",
          seenFrom = symbol.info,
          tpe = symbol.info,
          pos = pos,
          range = pos
        )
      case _: Literal if params.isInstanceOf[RangeParams] =>
        val symbol = tree.symbol
        toHover(
          symbol = symbol,
          keyword = "",
          seenFrom = null,
          tpe = tree.tpe,
          pos = pos,
          range = pos
        )
      case _ =>
        // Don't show hover for non-identifiers.
        None
    }
  }

  def seenFromType(tree0: Tree, symbol: Symbol): Type = {
    def qual(t: Tree): Tree =
      t match {
        case TreeApply(q, _) => qual(q)
        case Select(q, _) => q
        case Import(q, _) => q
        case t => t
      }
    try {
      val tree = qual(tree0)
      val pre = stabilizedType(tree)
      val memberType = pre.memberType(symbol)
      if (memberType.isErroneous) symbol.info
      else memberType
    } catch {
      case NonFatal(_) => symbol.info
    }
  }

  /**
   * Traverses up the parent tree nodes to the largest enclosing application node.
   *
   * Example: {{{
   *   original = println(List(1).map(_.toString))
   *   pos      = List(1).map
   *   expanded = List(1).map(_.toString)
   * }}}
   */
  def expandRangeToEnclosingApply(pos: Position): Tree = {
    def tryTail(enclosing: List[Tree]): Option[Tree] =
      enclosing match {
        case Nil => None
        case head :: tail =>
          head match {
            case TreeApply(qual, _) if qual.pos.includes(pos) =>
              tryTail(tail).orElse(Some(head))
            case New(_) =>
              tail match {
                case Nil => None
                case Select(_, _) :: next =>
                  tryTail(next)
                case _ =>
                  None
              }
            case _ =>
              None
          }
      }
    lastVisitedParentTrees match {
      case head :: tail =>
        tryTail(tail).getOrElse(head)
      case _ =>
        EmptyTree
    }
  }

  def toHover(
      symbol: Symbol,
      pos: Position
  ): Option[Hover] = {
    toHover(symbol, symbol.keyString, symbol.info, symbol.info, pos, pos)
  }

  def toHover(
      symbol: Symbol,
      keyword: String,
      seenFrom: Type,
      tpe: Type,
      pos: Position,
      range: Position
  ): Option[Hover] = {
    if (tpe == null || tpe.isErroneous || tpe == NoType) None
    else if (
      pos.start != pos.end && (symbol == null || symbol == NoSymbol || symbol.isErroneous)
    ) {
      val context = doLocateContext(pos)
      val history = new ShortenedNames(
        lookupSymbol = name => context.lookupSymbol(name, _ => true) :: Nil
      )
      val prettyType = metalsToLongString(tpe.widen.finalResultType, history)
      val hover = new Hover(HoverMarkup(prettyType).toMarkupContent)
      if (range.isRange) {
        hover.setRange(range.toLsp)
      }
      Some(hover)
    } else if (symbol == null || tpe.typeSymbol.isAnonymousClass) None
    else if (symbol.hasPackageFlag || symbol.hasModuleFlag) {
      Some(
        new Hover(
          new MarkupContent(
            MarkupKind.MARKDOWN,
            HoverMarkup(
              s"${symbol.javaClassSymbol.keyString} ${symbol.fullName}"
            )
          )
        )
      )
    } else {
      val context = doLocateContext(pos)
      val history = new ShortenedNames(
        lookupSymbol = name => context.lookupSymbol(name, _ => true) :: Nil
      )
      val symbolInfo =
        if (seenFrom.isErroneous) symbol.info
        else seenFrom
      val printer = new SignaturePrinter(
        symbol,
        history,
        symbolInfo.widen,
        includeDocs = true
      )
      val name =
        if (symbol.isConstructor) "this"
        else symbol.name.decoded
      val flags = List(symbolFlagString(symbol), keyword, name)
        .filterNot(_.isEmpty)
        .mkString(" ")
      val prettyType = metalsToLongString(tpe.widen.finalResultType, history)
      val macroSuffix =
        if (symbol.isMacro) " = macro"
        else ""
      val prettySignature = printer.defaultMethodSignature(flags) + macroSuffix
      val docstring =
        if (metalsConfig.isHoverDocumentationEnabled) {
          symbolDocumentation(symbol).fold("")(_.docstring())
        } else {
          ""
        }
      val markdown = HoverMarkup(
        prettyType,
        prettySignature,
        docstring,
        pos.start != pos.end || !prettySignature.endsWith(prettyType)
      )
      val hover = new Hover(markdown.toMarkupContent)
      if (range.isRange) {
        hover.setRange(range.toLsp)
      }
      Some(hover)
    }
  }

  def symbolFlagString(sym: Symbol): String = {
    var mask = sym.flagMask
    // Strip case modifier off non-class symbols like synthetic apply/copy.
    if (sym.isCase && !sym.isClass) mask &= ~gf.CASE
    sym.flagString(mask)
  }

  private def typedHoverTreeAt(
      pos: Position,
      unit: RichCompilationUnit
  ): Tree = {
    typeCheck(unit)
    val typedTree = locateTree(pos)
    typedTree match {
      case Import(qual, _) if qual.pos.includes(pos) =>
        qual.findSubtree(pos)
      case Apply(fun, args)
          if !fun.pos.includes(pos) &&
            !isForSynthetic(typedTree) =>
        // Looks like a named argument, try the arguments.
        val arg = args.collectFirst {
          case arg if treePos(arg).includes(pos) =>
            arg match {
              case Block(_, expr) if treePos(expr).includes(pos) =>
                // looks like a desugaring of named arguments in different order from definition-site.
                expr
              case a => a
            }
        }
        arg.getOrElse(typedTree)
      case t => t
    }
  }

  lazy val isForName: Set[Name] = Set[Name](
    nme.map,
    nme.withFilter,
    nme.flatMap,
    nme.foreach
  )
  def isForSynthetic(gtree: Tree): Boolean = {
    def isForComprehensionSyntheticName(select: Select): Boolean = {
      select.pos == select.qualifier.pos && isForName(select.name)
    }
    gtree match {
      case Apply(fun, List(_: Function)) => isForSynthetic(fun)
      case TypeApply(fun, _) => isForSynthetic(fun)
      case gtree: Select if isForComprehensionSyntheticName(gtree) => true
      case _ => false
    }
  }

}
