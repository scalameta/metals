package scala.meta.internal.pc

import scala.reflect.internal.util.Position
import scala.reflect.internal.{Flags => gf}

import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

class HoverProvider(val compiler: MetalsGlobal, params: OffsetParams)(implicit
    reportContext: ReportContext
) {
  import compiler._

  def hover(): Option[HoverSignature] = params match {
    case range: RangeParams =>
      range.trimWhitespaceInRange.flatMap(hoverOffset)
    case _ if params.isWhitespace && params.prevIsWhitespaceOrDelimeter => None
    case _ => hoverOffset(params)
  }

  def hoverOffset(params: OffsetParams): Option[HoverSignature] = {
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

    def report = {
      val hasErroneousType =
        if (tree.tpe != null) tree.tpe.isErroneous
        else "type null"
      val fileName = params.uri().toString()
      val posId =
        if (tree.pos.isDefined) tree.pos.start
        else pos.start

      Report(
        "empty-hover-scala2",
        s"""|pos: ${pos.toLsp}
            |
            |is error: $hasErroneousType
            |symbol: ${tree.symbol}
            |tpe: ${tree.tpe}
            |
            |tree:
            |```scala
            |$tree
            |```
            |
            |full tree:
            |```scala
            |${unit.body}
            |```
            |""".stripMargin,
        s"empty hover in $fileName",
        id = Some(s"$fileName::$posId"),
        path = Some(fileName)
      )
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
            expanded.pos,
            Some(report)
          )
        } else {
          for {
            sym <- Option(tree.symbol)
            tpe <- Option(tree.tpe)
            seenFrom = seenFromType(tree, sym)
            hover <- toHover(
              sym,
              sym.keyString,
              seenFrom,
              tpe,
              pos,
              tree.pos,
              Some(report)
            )
          } yield hover
        }
      case UnApply(fun, _) if fun.symbol != null =>
        toHover(
          fun.symbol,
          fun.symbol.keyString,
          seenFromType(tree, fun.symbol),
          tree.tpe,
          pos,
          pos,
          Some(report)
        )
      // Def, val or val definition, example `val x: Int = 1`
      // Matches only if the cursor is over the definition name.
      case v: ValOrDefDef
          if (v.namePosition
            .includes(pos) || pos.includes(
            v.namePosition
          )) && v.symbol != null =>
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
          v.pos,
          Some(report)
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
          range = pos,
          Some(report)
        )
      case _: Literal if params.isInstanceOf[RangeParams] =>
        val symbol = tree.symbol
        toHover(
          symbol = symbol,
          keyword = "",
          seenFrom = null,
          tpe = tree.tpe,
          pos = pos,
          range = pos,
          Some(report)
        )
      // Class, object, type definitions, matches only if the cursor is over the definition name.
      case t: MemberDef
          if (t.namePosition
            .includes(pos) || pos.includes(
            t.namePosition
          )) && t.symbol != null =>
        val symbol = tree.symbol
        val tpe = seenFromType(tree, symbol)
        toHover(
          symbol = symbol,
          keyword = symbol.keyString,
          seenFrom = tpe,
          tpe = tpe,
          pos = pos,
          range = t.namePosition,
          Some(report)
        )
      case Block((valDef @ ValDef(_, _, _, rhs)) :: Nil, FunctionApply(app))
          if Option(valDef.symbol).exists(_.isSynthetic) =>
        def locateTree(
            tree: Tree,
            enclosingApply: Option[Tree]
        ): Option[(Tree, Option[Tree])] =
          tree match {
            case ident: Ident if ident.namePosition.encloses(pos) =>
              Some((ident, enclosingApply))
            case select: Select if select.namePosition.encloses(pos) =>
              Some((select, enclosingApply))
            case Select(qualifier, _) => locateTree(qualifier, None)
            case app @ TreeApply(fun, _) if fun.pos.encloses(pos) =>
              locateTree(fun, enclosingApply.orElse(Some(app)))
            case TreeApply(_, args) =>
              argAtPos(args, pos).map(tree => (tree, None))
            case _ => None
          }

        val chosenPart = if (rhs.pos.encloses(pos)) rhs else app
        for {
          (locatedTree, enclosingApply) <- locateTree(chosenPart, None)
          symbol <- Option(locatedTree.symbol)
          expanded = enclosingApply match {
            case Some(app) if app.symbol != null && app.tpe != null => app
            case _ => locatedTree
          }
          tpe <- Option(expanded.tpe)
          hover <- toHover(
            symbol = symbol,
            keyword = symbol.keyString,
            seenFrom = seenFromType(tree, symbol),
            tpe = tpe,
            pos = pos,
            range = expanded.pos,
            Some(report)
          )
        } yield hover
      case _ =>
        // Don't show hover for non-identifiers.
        None
    }
  }

  def toHover(
      symbol: Symbol,
      pos: Position
  ): Option[HoverSignature] = {
    toHover(symbol, symbol.keyString, symbol.info, symbol.info, pos, pos)
  }

  def toHover(
      symbol: Symbol,
      keyword: String,
      seenFrom: Type,
      tpe: Type,
      pos: Position,
      range: Position,
      report: => Option[Report] = None
  ): Option[HoverSignature] = {
    val result =
      if (tpe == null || tpe.isErroneous || tpe == NoType) None
      else if (
        pos.start != pos.end && (symbol == null || symbol == NoSymbol || symbol.isErroneous)
      ) {
        val context = doLocateContext(pos)
        val history = new ShortenedNames(
          lookupSymbol = name => context.lookupSymbol(name, _ => true) :: Nil
        )
        val prettyType = metalsToLongString(tpe.widen.finalResultType, history)
        val lspRange = if (range.isRange) Some(range.toLsp) else None
        Some(
          new ScalaHover(expressionType = Some(prettyType), range = lspRange)
        )
      } else if (symbol == null || tpe.typeSymbol.isAnonymousClass) None
      else if (symbol.hasPackageFlag || symbol.hasModuleFlag) {
        Some(
          new ScalaHover(
            expressionType = Some(
              s"${symbol.javaClassSymbol.keyString} ${symbol.fullName}"
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
        val prettySignature =
          printer.defaultMethodSignature(flags) + macroSuffix
        val docstring =
          if (metalsConfig.isHoverDocumentationEnabled) {
            symbolDocumentation(symbol).fold("")(_.docstring())
          } else {
            ""
          }
        Some(
          ScalaHover(
            expressionType = Some(prettyType),
            symbolSignature = Some(prettySignature),
            docstring = Some(docstring),
            forceExpressionType =
              pos.start != pos.end || !prettySignature.endsWith(prettyType),
            range = if (range.isRange) Some(range.toLsp) else None
          )
        )
      }

    if (result.isEmpty) {
      report.foreach(reportContext.unsanitized.create(_, ifVerbose = true))
    }
    result
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
        argAtPos(args, pos).getOrElse(typedTree)
      case t => t
    }
  }

  private def argAtPos(args: List[Tree], pos: Position) =
    args.collectFirst {
      case arg if treePos(arg).includes(pos) =>
        arg match {
          case Block(_, expr) if treePos(expr).includes(pos) =>
            // looks like a desugaring of named arguments in different order from definition-site.
            expr
          case a => a
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

  object FunctionApply {
    def unapply(tree: Tree): Option[Apply] =
      Option(tree).collect {
        case Function(_, apply: Apply) => apply
        case Function(_, FunctionApply(apply)) => apply
      }
  }

}
