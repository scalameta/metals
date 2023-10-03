package scala.meta.internal.pc

import scala.collection.JavaConverters.*

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition

class PcSyntheticDecorationsProvider(
    driver: InteractiveDriver,
    params: SyntheticDecorationsParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  def provide(): List[SyntheticDecoration] =
    Collector
      .resultAllOccurences(includeSynthetics = true)()
      .toList
      .flatten

  object Collector
      extends PcCollector[Option[SyntheticDecoration]](driver, params):

    val definitions = IndexedContext(ctx).ctx.definitions
    val withoutTypes = params.declsWithoutTypesRanges().asScala.toSet

    override def collect(parent: Option[tpd.Tree])(
        tree: tpd.Tree,
        pos: SourcePosition,
        symbol: Option[Symbol],
    ): Option[SyntheticDecoration] =
      val sym = symbol.fold(tree.symbol)(identity)
      if sym == null then None
      else
        parent
          .zip(Some(tree))
          .collectFirst {
            case (Apply(fun, args), _)
                if fun.span == pos.span && pos.span.isSynthetic =>
              if params.implicitConversions then
                val lastArgPos =
                  args.lastOption.map(_.sourcePos).getOrElse(pos).toLsp
                lastArgPos.setStart(pos.toLsp.getStart())
                Some(
                  Decoration(
                    lastArgPos,
                    sym.decodedName,
                    DecorationKind.ImplicitConversion,
                  )
                )
              else None
            case (Apply(_, args), _)
                if args.exists(_.span == pos.span) && pos.span.isSynthetic =>
              if params.implicitParameters then
                Some(
                  Decoration(
                    pos.toLsp,
                    sym.decodedName,
                    DecorationKind.ImplicitParameter,
                  )
                )
              else None
            case (TypeApply(sel: Select[?], _), _)
                if syntheticTupleApply(sel) =>
              None
            case (TypeApply(fun, _), TypeTree())
                if !pos.span.isZeroExtent && pos.span.exists // inferred type parameters with zero extent span are mostly incorrect
                =>
              if params.inferredTypes then
                val tpe = tree.tpe.stripTypeVar.widen.finalResultType
                val label = toLabel(tpe, pos)
                Some(
                  Decoration(
                    pos.endPos.toLsp,
                    label,
                    DecorationKind.TypeParameter,
                  )
                )
              else None
          }
          .getOrElse {
            if pos.span.exists && !pos.span.isZeroExtent && withoutTypes(
                pos.toLsp
              )
            then
              val tpe = sym.info.widen.finalResultType
              val kind = DecorationKind.InferredType // inferred type
              val label = toLabel(tpe, pos)
              Some(Decoration(pos.toLsp, label, kind))
            else None
          }
      end if
    end collect

    private def toLabel(
        tpe: Type,
        pos: SourcePosition,
    ): String =
      val tpdPath =
        Interactive.pathTo(unit.tpdTree, pos.span)
      val indexedCtx = IndexedContext(MetalsInteractive.contextOfPath(tpdPath))
      val shortenedNames = new ShortenedNames(indexedCtx)
      val printer = MetalsPrinter.forInferredType(
        shortenedNames,
        indexedCtx,
        symbolSearch,
        includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
      )
      def optDealias(tpe: Type): Type =
        def isInScope(tpe: Type): Boolean =
          tpe match
            case tref: TypeRef =>
              indexedCtx.lookupSym(
                tref.currentSymbol
              ) == IndexedContext.Result.InScope
            case AppliedType(tycon, args) =>
              isInScope(tycon) && args.forall(isInScope)
            case _ => true
        if isInScope(tpe)
        then tpe
        else tpe.metalsDealias(using indexedCtx.ctx)

      val dealiased = optDealias(tpe)
      printer.tpe(dealiased)
    end toLabel

    private def syntheticTupleApply(sel: Select[?]): Boolean =
      if definitions.isTupleNType(sel.symbol.info.finalResultType) then
        sel match
          case Select(tupleClass: Ident[?], _)
              if !tupleClass.span.isZeroExtent &&
                tupleClass.span.exists &&
                tupleClass.name.startsWith("Tuple") =>
            val pos = tupleClass.sourcePos
            !sourceText.slice(pos.start, pos.end).mkString.startsWith("Tuple")
          case _ => true
      else false

  end Collector

end PcSyntheticDecorationsProvider
