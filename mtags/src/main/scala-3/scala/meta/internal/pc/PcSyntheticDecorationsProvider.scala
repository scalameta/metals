package scala.meta.internal.pc

import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition

class PcSyntheticDecorationsProvider(
    driver: InteractiveDriver,
    params: RangeParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  def provide(): List[SyntheticDecoration] =
    val trees = Collector.treesInRange(params)
    Collector
      .resultAllOccurences(includeSynthetics = true)(trees)
      .toList
      .flatten
      .sortWith((n1, n2) => n1.range().lt(n2.range()))

  object Collector
      extends PcCollector[Option[SyntheticDecoration]](driver, params):
    val indexedCtx = IndexedContext(ctx)
    val shortenedNames = new ShortenedNames(indexedCtx)
    val definitions = indexedCtx.ctx.definitions
    val printer = MetalsPrinter.forInferredType(
      shortenedNames,
      indexedCtx,
      symbolSearch,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
    )

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
              val lastArgPos =
                args.lastOption.map(_.sourcePos).getOrElse(pos).toLsp
              lastArgPos.setStart(pos.toLsp.getStart())
              Decoration(
                lastArgPos,
                sym.decodedName,
                DecorationKind.ImplicitConversion,
                Some(semanticdbSymbol(sym)),
              )
            case (Apply(_, args), _)
                if args.exists(_.span == pos.span) && pos.span.isSynthetic =>
              Decoration(
                pos.toLsp,
                sym.decodedName,
                DecorationKind.ImplicitParameter,
                Some(semanticdbSymbol(sym)),
              )
            case (TypeApply(fun, _), TypeTree())
                if !definitions.isTupleNType(fun.symbol.info.finalResultType) &&
                  !pos.span.isZeroExtent // inferred type parameters with zero extent span are mostly incorrect
                =>
              val tpe = optDealias(tree.tpe)
              val parts = partsFromType(tpe)
              val labelParts = makeLabelParts(parts, tpe)
              Decoration(
                pos.endPos.toLsp,
                labelParts,
                DecorationKind.TypeParameter,
              )
          }
          .orElse {
            if !pos.span.isZeroExtent then
              val tpe = optDealias(sym.info)
              val parts = partsFromType(tpe)
              val kind = DecorationKind.InferredType // inferred type
              val labelParts = makeLabelParts(parts, tpe)
              Some(Decoration(pos.toLsp, labelParts, kind))
            else None
          }
      end if
    end collect

    private def optDealias(tpe: Type): Type =
      tpe.finalResultType.metalsDealias
    end optDealias

    private def partsFromType(tpe: Type): List[TypeWithName] =
      val acc = NamedPartsAccumulator.apply(_ => true)
      acc.apply(Nil, tpe).map(TypeWithName(_)).distinctBy(_.name)

    private def makeLabelParts(
        parts: List[TypeWithName],
        tpe: Type,
    ): List[LabelPart] =
      val buffer = ListBuffer.empty[LabelPart]
      var current = 0
      val tpeStr = printer.tpe(tpe)
      parts
        .flatMap { tp =>
          allIndexesWhere(tp.name, tpeStr).map((_, tp))
          // find all occurences of str in tpe
        }
        .sortWith { case ((idx1, tp1), (idx2, tp2)) =>
          if idx1 == idx2 then tp1.name.length > tp2.name.length
          else idx1 < idx2
        }
        .foreach { case (index, tp) =>
          if index >= current then
            buffer += labelPart(tpeStr.substring(current, index))
            buffer += labelPart(tp.name, Some(tp.tpe.typeSymbol))
            current = index + tp.name.length
        }
      buffer += labelPart(tpeStr.substring(current, tpeStr.length))
      buffer.toList.filter(_.label.nonEmpty)
    end makeLabelParts

    private def labelPart(str: String, symbol: Option[Symbol] = None) =
      val symbolStr = symbol.map(semanticdbSymbol).getOrElse("")
      LabelPart(str, symbolStr)

    private def semanticdbSymbol(sym: Symbol): String =
      SemanticdbSymbols.symbolName(sym)

    def allIndexesWhere(
        str: String,
        tpe: String,
    ): List[Int] =
      val buffer = ListBuffer.empty[Int]
      var current = 0
      while current < tpe.length do
        val index = tpe.indexOf(str, current)
        if index == -1 then current = tpe.length
        else
          buffer += index
          current = index + str.length
      buffer.toList
    end allIndexesWhere

    case class TypeWithName(tpe: Type, name: String)
    object TypeWithName:
      def apply(tpe: Type): TypeWithName =
        TypeWithName(tpe, printer.tpe(tpe))

  end Collector

end PcSyntheticDecorationsProvider
