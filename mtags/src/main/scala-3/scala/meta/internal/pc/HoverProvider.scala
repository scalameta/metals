package scala.meta.internal.pc

import java.{util as ju}

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition

object HoverProvider:

  def hover(
      params: OffsetParams,
      driver: InteractiveDriver,
      search: SymbolSearch,
  ): ju.Optional[HoverSignature] =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)

    given ctx: Context = driver.currentCtx
    val pos = driver.sourcePosition(params)
    val trees = driver.openedTrees(uri)
    val indexedContext = IndexedContext(ctx)

    def typeFromPath(path: List[Tree]) =
      if path.isEmpty then NoType else path.head.tpe

    val path = Interactive.pathTo(trees, pos)
    val tp = typeFromPath(path)
    val tpw = tp.widenTermRefExpr
    // For expression we need to find all enclosing applies to get the exact generic type
    val enclosing = path.expandRangeToEnclosingApply(pos)

    if tp.isError || tpw == NoType || tpw.isError || path.isEmpty
    then ju.Optional.empty()
    else
      val skipCheckOnName =
        !pos.isPoint // don't check isHoveringOnName for RangeHover

      val printerContext =
        driver.compilationUnits.get(uri) match
          case Some(unit) =>
            val newctx =
              ctx.fresh.setCompilationUnit(unit)
            MetalsInteractive.contextOfPath(enclosing)(using newctx)
          case None => ctx
      val printer = MetalsPrinter.standard(
        IndexedContext(printerContext),
        search,
        includeDefaultParam = MetalsPrinter.IncludeDefaultParam.Include,
      )
      MetalsInteractive.enclosingSymbolsWithExpressionType(
        enclosing,
        pos,
        indexedContext,
        skipCheckOnName,
      ) match
        case Nil =>
          fallbackToDynamics(path, printer)
        case (symbol, tpe) :: _
            if symbol.name == nme.selectDynamic || symbol.name == nme.applyDynamic =>
          fallbackToDynamics(path, printer)
        case symbolTpes @ ((symbol, tpe) :: _) =>
          val exprTpw = tpe.widenTermRefExpr.metalsDealias
          val hoverString =
            tpw match
              // https://github.com/lampepfl/dotty/issues/8891
              case tpw: ImportType =>
                printer.hoverSymbol(symbol, symbol.paramRef)
              case _ =>
                val (tpe, sym) =
                  if symbol.isType then (symbol.typeRef, symbol)
                  else enclosing.head.seenFrom(symbol)

                val finalTpe =
                  if tpe != NoType then tpe
                  else tpw

                printer.hoverSymbol(sym, finalTpe)
            end match
          end hoverString

          val docString = symbolTpes
            .flatMap(symTpe => search.symbolDocumentation(symTpe._1))
            .map(_.docstring)
            .mkString("\n")
          printer.expressionType(exprTpw) match
            case Some(expressionType) =>
              val forceExpressionType =
                !pos.span.isZeroExtent || (
                  !hoverString.endsWith(expressionType) &&
                    !symbol.isType &&
                    !symbol.is(Module) &&
                    !symbol.flags.isAllOf(EnumCase)
                )
              ju.Optional.of(
                new ScalaHover(
                  expressionType = Some(expressionType),
                  symbolSignature = Some(hoverString),
                  docstring = Some(docString),
                  forceExpressionType = forceExpressionType,
                )
              )
            case _ =>
              ju.Optional.empty
          end match
      end match
    end if
  end hover

  extension (pos: SourcePosition)
    private def isPoint: Boolean = pos.start == pos.end

  private def fallbackToDynamics(
      path: List[Tree],
      printer: MetalsPrinter,
  )(using Context): ju.Optional[HoverSignature] = path match
    case SelectDynamicExtractor(sel, n, name) =>
      def findRefinement(tp: Type): ju.Optional[HoverSignature] =
        tp match
          case RefinedType(info, refName, tpe) if name == refName.toString() =>
            val tpeString =
              if n == nme.selectDynamic then s": ${printer.tpe(tpe.resultType)}"
              else printer.tpe(tpe)
            ju.Optional.of(
              new ScalaHover(
                expressionType = Some(tpeString),
                symbolSignature = Some(s"def $name$tpeString"),
              )
            )
          case RefinedType(info, _, _) =>
            findRefinement(info)
          case _ => ju.Optional.empty()

      findRefinement(sel.tpe.termSymbol.info.dealias)
    case _ =>
      ju.Optional.empty()

end HoverProvider

object SelectDynamicExtractor:
  def unapply(path: List[Tree])(using Context) =
    path match
      // the same tests as below, since 3.3.1-RC1 path starts with Select
      case Select(_, _) :: Apply(
            Select(Apply(reflSel, List(sel)), n),
            List(Literal(Constant(name: String))),
          ) :: _
          if (n == nme.selectDynamic || n == nme.applyDynamic) &&
            nme.reflectiveSelectable == reflSel.symbol.name =>
        Some(sel, n, name)
      // tests `structural-types` and `structural-types1` in HoverScala3TypeSuite
      case Apply(
            Select(Apply(reflSel, List(sel)), n),
            List(Literal(Constant(name: String))),
          ) :: _
          if (n == nme.selectDynamic || n == nme.applyDynamic) &&
            nme.reflectiveSelectable == reflSel.symbol.name =>
        Some(sel, n, name)
      // the same tests as below, since 3.3.1-RC1 path starts with Select
      case Select(_, _) :: Apply(
            Select(sel, n),
            List(Literal(Constant(name: String))),
          ) :: _ if n == nme.selectDynamic || n == nme.applyDynamic =>
        Some(sel, n, name)
      // tests `selectable`,  `selectable2` and `selectable-full` in HoverScala3TypeSuite
      case Apply(
            Select(sel, n),
            List(Literal(Constant(name: String))),
          ) :: _ if n == nme.selectDynamic || n == nme.applyDynamic =>
        Some(sel, n, name)
      case _ => None
    end match
  end unapply
end SelectDynamicExtractor
