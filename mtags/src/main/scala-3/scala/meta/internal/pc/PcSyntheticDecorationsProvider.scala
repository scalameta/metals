package scala.meta.internal.pc

import dotty.tools.dotc.interactive.InteractiveDriver
import scala.meta.pc.VirtualFileParams
import scala.meta.pc.SyntheticDecoration
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.ast.tpd
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.SymbolSearch
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import dotty.tools.dotc.core.Types.*
import scala.meta.internal.metals.ReportContext
import dotty.tools.dotc.ast.Trees.*

class PcSyntheticDecorationsProvider(
    driver: InteractiveDriver,
    params: VirtualFileParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  def provide(): List[SyntheticDecoration] =
    Collector
      .resultAllOccurences(includeSynthetics = true)
      .toList
      .flatten
      .sortWith((n1, n2) => n1.range().lt(n2.range()))

  object Collector
      extends PcCollector[Option[SyntheticDecoration]](driver, params):
    val indexedCtx = IndexedContext(ctx)
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
      if isInScope(tpe.finalResultType)
      then tpe.finalResultType
      else tpe.finalResultType.metalsDealias
    end optDealias

    def printType(tpe: Type) =
      printer.tpe(optDealias(tpe))

    override def collect(parent: Option[tpd.Tree])(
        tree: tpd.Tree,
        pos: SourcePosition,
        symbol: Option[Symbol],
    ): Option[SyntheticDecoration] =
      val sym = symbol.fold(tree.symbol)(identity)
      parent
        .zip(Some(tree))
        .collectFirst {
          case (Apply(fun, args), _)
              if fun.span == pos.span && pos.span.isSynthetic =>
            val lastArgPos =
              args.lastOption.map(_.sourcePos).getOrElse(pos).toLsp
            lastArgPos.setStart(pos.toLsp.getStart())
            Decoration(
              lastArgPos,
              sym.decodedName,
              DecorationKind.ImplicitConversion,
            )
          case (Apply(_, args), _)
              if args.exists(_.span == pos.span) && pos.span.isSynthetic =>
            Decoration(
              pos.toLsp,
              sym.decodedName,
              DecorationKind.ImplicitParameter,
            )
          case (TypeApply(_, _), TypeTree()) =>
            Decoration(
              pos.endPos.toLsp,
              printType(tree.tpe),
              DecorationKind.TypeParameter,
            )

        }
        .orElse {
          val tpe = printType(sym.info)
          val kind = DecorationKind.InferredType // inferred type
          Some(Decoration(pos.toLsp, tpe, kind))
        }
    end collect

  end Collector

end PcSyntheticDecorationsProvider
