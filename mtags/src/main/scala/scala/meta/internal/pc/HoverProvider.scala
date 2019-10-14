package scala.meta.internal.pc

import org.eclipse.lsp4j.jsonrpc.messages.{Either => JEither}
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkedString
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.reflect.internal.{Flags => gf}
import scala.meta.internal.jdk.CollectionConverters._
import scala.util.control.NonFatal

class HoverProvider(val compiler: MetalsGlobal, params: OffsetParams) {

  import compiler._

  def hover(): Option[Hover] = {
    if (params.isWhitespace) {
      None
    } else {
      val (unit, pos, tree) = createCompilationUnit(params)
      tree match {
        case i @ Import(_, selectors) =>
          for {
            member <- i.selector(pos)
            hover <- toHover(member, pos)
          } yield hover
        case _: Select | _: Apply | _: TypeApply | _: Ident =>
          val expanded = expandRangeToEnclosingApply(pos)
          if (expanded != null &&
            expanded.tpe != null &&
            tree.symbol != null &&
            expanded.symbol != null) {
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
                s"${symbol.javaClassSymbol.keyString} ${symbol.fullName}"
              )
            )
          ).asJava
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
}
