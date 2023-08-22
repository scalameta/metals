package scala.meta.internal.pc

import scala.collection.JavaConverters.*
import scala.collection.mutable.ListBuffer

import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.ShortenedNames
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Symbols.NoSymbol
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
    val trees = Collector.treesInRange(params)
    Collector
      .resultAllOccurences(includeSynthetics = true)(trees)
      .toList
      .flatten

  object Collector
      extends PcCollector[Option[SyntheticDecoration]](driver, params):

    val definitions = IndexedContext(ctx).ctx.definitions
    val withoutTypes = params.withoutTypes().asScala.toSet

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
                    Some(semanticdbSymbol(sym)),
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
                    Some(semanticdbSymbol(sym)),
                  )
                )
              else None
            case (TypeApply(fun, _), TypeTree())
                if !definitions.isTupleNType(fun.symbol.info.finalResultType) &&
                  !pos.span.isZeroExtent // inferred type parameters with zero extent span are mostly incorrect
                =>
              if params.inferredTypes then
                val tpe = tree.tpe.stripTypeVar.widen.finalResultType
                val labelParts = toLabelParts(tpe, pos)
                Some(
                  Decoration(
                    pos.endPos.toLsp,
                    labelParts,
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
              val labelParts = toLabelParts(tpe, pos)
              Some(Decoration(pos.toLsp, labelParts, kind))
            else None
          }
      end if
    end collect

    private def partsFromType(tpe: Type): List[TypeWithName] =
      val acc = NamedPartsAccumulator.apply(_ => true)
      acc
        .apply(Nil, tpe)
        .filter(_.symbol != NoSymbol)
        .map(t =>
          TypeWithName(t.symbol.decodedName, semanticdbSymbol(t.symbol))
        )

    private def toLabelParts(
        tpe: Type,
        pos: SourcePosition,
    ): List[LabelPart] =
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
      val parts = partsFromType(dealiased)
      makeLabelParts(parts, printer.tpe(dealiased))
    end toLabelParts

    private def makeLabelParts(
        parts: List[TypeWithName],
        tpeStr: String,
    ): List[LabelPart] =
      val buffer = ListBuffer.empty[LabelPart]
      var current = 0
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
            buffer += labelPart(tp.name, Some(tp.semanticdbSymbol))
            current = index + tp.name.length
        }
      buffer += labelPart(tpeStr.substring(current, tpeStr.length))
      buffer.toList.filter(_.label.nonEmpty)
    end makeLabelParts

    private def labelPart(str: String, symbol: Option[String] = None) =
      val symbolStr = symbol.getOrElse("")
      LabelPart(str, symbolStr)

    private def semanticdbSymbol(sym: Symbol): String =
      SemanticdbSymbols.symbolName(sym)

    private def allIndexesWhere(
        str: String,
        in: String,
    ): List[Int] =
      val buffer = ListBuffer.empty[Int]
      var index = in.indexOf(str)
      while index >= 0 do
        buffer += index
        index = in.indexOf(str, index + 1)
      buffer.toList

    case class TypeWithName(name: String, semanticdbSymbol: String)

  end Collector

end PcSyntheticDecorationsProvider
