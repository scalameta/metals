package scala.meta.internal.pc

import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.reflect.internal.{Flags => gf}
import scala.collection.JavaConverters._

class HoverProvider(val compiler: MetalsGlobal, params: OffsetParams) {
  import compiler._

  def hover(): Option[Hover] = {
    if (params.offset() < 0 ||
      params.offset() >= params.text().length ||
      params.text().charAt(params.offset()).isWhitespace) {
      None
    } else {
      val unit = addCompilationUnit(
        code = params.text(),
        filename = params.filename(),
        cursor = None
      )
      val pos = unit.position(params.offset())
      val tree = typedHoverTreeAt(pos)
      tree match {
        case Import(qual, selectors) =>
          for {
            sel <- selectors.reverseIterator.find(_.namePos <= pos.start)
            member = qual.symbol.info.member(sel.name)
            hover <- toHover(member, pos)
          } yield hover
        case _: Select | _: Apply | _: TypeApply | _: Ident =>
          val expanded = expandRangeToEnclosingApply(pos)
          if (expanded != null &&
            expanded.tpe != null &&
            tree.symbol != null) {
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
        case v: ValOrDefDef if v.namePos.includes(pos) && v.symbol != null =>
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
        case _ =>
          // Don't show hover for non-identifiers.
          None
      }
    }
  }

  def seenFromType(tree0: Tree, symbol: Symbol): Type = {
    def qual(t: Tree): Tree = t match {
      case TreeApply(q, _) => qual(q)
      case Select(q, _) => q
      case Import(q, _) => q
      case t => t
    }
    val tree = qual(tree0)
    val pre = stabilizedType(tree)
    val memberType = pre.memberType(symbol)
    if (memberType.isErroneous) symbol.info
    else memberType
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
    def tryTail(enclosing: List[Tree]): Option[Tree] = enclosing match {
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
    lastEnclosing match {
      case head :: tail =>
        tryTail(tail) match {
          case Some(value) =>
            typedTreeAt(value.pos)
          case None =>
            head
        }
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
    else if (symbol == null || symbol == NoSymbol || symbol.isErroneous) None
    else if (symbol.info.finalResultType.isInstanceOf[ClassInfoType]) None
    else if (symbol.hasPackageFlag || symbol.hasModuleFlag) {
      Some(
        new Hover(
          List(
            JEither.forRight[String, MarkedString](
              new MarkedString(
                "scala",
                s"${symbol.keyString} ${symbol.fullName}"
              )
            )
          ).asJava
        )
      )
    } else {
      val context = doLocateContext(pos)
      def widen(t: Type): Type =
        if (symbol.isLocallyDefinedSymbol) {
          // NOTE(olafur) Dealias type for local symbols to avoid unwanted `x.type` singleton
          // types for cases like:
          //   for (x <- List(1); if @@x > 1) println(x)
          // We might want to refine this heuristic down the road to either widen more aggressively
          // or less aggressively as we gain more experience.
          t.widen
        } else {
          t
        }
      val history = new ShortenedNames(
        lookupSymbol = name => context.lookupSymbol(name, _ => true) :: Nil
      )
      val symbolInfo =
        if (seenFrom.isErroneous) symbol.info
        else seenFrom
      val printer = new SignaturePrinter(
        symbol,
        history,
        widen(symbolInfo),
        includeDocs = true
      )
      val name =
        if (symbol.isConstructor) "this"
        else symbol.name.decoded
      val flags = List(symbolFlagString(symbol), keyword, name)
        .filterNot(_.isEmpty)
        .mkString(" ")
      val prettyType = metalsToLongString(widen(tpe).finalResultType, history)
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
      val markdown = HoverMarkup(prettyType, prettySignature, docstring)
      val hover = new Hover(markdown.toMarkupContent)
      if (range.isRange) {
        hover.setRange(range.toLSP)
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

  private def typedHoverTreeAt(pos: Position): Tree = {
    def loop(tree: Tree): Tree = tree match {
      case Select(qual, _) if qual.pos.includes(pos) => loop(qual)
      case t => t
    }
    typedTreeAt(pos) match {
      case Import(qual, _) if qual.pos.includes(pos) => loop(qual)
      case t => t
    }
  }
}
