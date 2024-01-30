package scala.meta.internal.pc

import java.{util as ju}

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.ReportContext
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
  )(implicit reportContext: ReportContext): ju.Optional[HoverSignature] =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)
    val unit = driver.compilationUnits.get(uri)

    given ctx: Context =
      val ctx = driver.currentCtx
      unit.map(ctx.fresh.setCompilationUnit).getOrElse(ctx)
    val pos = driver.sourcePosition(params)
    val path = unit
      .map(unit => Interactive.pathTo(unit.tpdTree, pos.span))
      .getOrElse(Interactive.pathTo(driver.openedTrees(uri), pos))
    val indexedContext = IndexedContext(ctx)
    def typeFromPath(path: List[Tree]) =
      if path.isEmpty then NoType else path.head.tpe

    val tp = typeFromPath(path)
    val tpw = tp.widenTermRefExpr
    // For expression we need to find all enclosing applies to get the exact generic type
    val enclosing = path.expandRangeToEnclosingApply(pos)

    if tp.isError || tpw == NoType || tpw.isError || path.isEmpty
    then
      def report =
        val posId =
          if path.isEmpty || path.head.sourcePos == null || !path.head.sourcePos.exists
          then pos.start
          else path.head.sourcePos.start
        StandardReport(
          "empty-hover-scala3",
          s"""|pos: ${pos.toLsp}
              |
              |tp: $tp
              |has error: ${tp.isError}
              |
              |tpw: $tpw
              |has error: ${tpw.isError}
              |
              |path:
              |${path
               .map(tree => s"""|```scala
                                |${tree.show}
                                |```
                                |""".stripMargin)
               .mkString("\n")}
              |trees:
              |${unit
               .map(u => List(u.tpdTree))
               .getOrElse(driver.openedTrees(uri).map(_.tree))
               .map(tree => s"""|```scala
                                |${tree.show}
                                |```
                                |""".stripMargin)
               .mkString("\n")}
              |""".stripMargin,
          s"empty hover in $uri",
          id = Some(s"$uri::$posId"),
          path = Some(uri.toString),
        )
      end report
      reportContext.unsanitized.create(report)
      ju.Optional.empty()
    else
      val skipCheckOnName =
        !pos.isPoint // don't check isHoveringOnName for RangeHover
      val printerCtx = Interactive.contextOfPath(path)
      val printer = MetalsPrinter.standard(
        IndexedContext(printerCtx),
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
                  contextInfo = printer.getUsedRenamesInfo(),
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
      def findRefinement(tp: Type): Option[HoverSignature] =
        tp match
          case RefinedType(_, refName, tpe) if name == refName.toString() =>
            val tpeString =
              if n == nme.selectDynamic then s": ${printer.tpe(tpe.resultType)}"
              else printer.tpe(tpe)

            val valOrDef =
              if n == nme.selectDynamic && !tpe.isInstanceOf[ExprType]
              then "val"
              else "def"

            Some(
              new ScalaHover(
                expressionType = Some(tpeString),
                symbolSignature = Some(s"$valOrDef $name$tpeString"),
                contextInfo = printer.getUsedRenamesInfo(),
              )
            )
          case RefinedType(parent, _, _) =>
            findRefinement(parent)
          case _ => None

      val refTpe = sel.tpe.metalsDealias match
        case r: RefinedType => Some(r)
        case t: (TermRef | TypeProxy) => Some(t.termSymbol.info.metalsDealias)
        case _ => None

      refTpe.flatMap(findRefinement).asJava
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
