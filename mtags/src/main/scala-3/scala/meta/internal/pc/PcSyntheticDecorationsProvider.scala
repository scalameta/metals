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
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.NoSymbol

class PcSyntheticDecorationsProvider(
    driver: InteractiveDriver,
    params: VirtualFileParams,
    symbolSearch: SymbolSearch,
)(using ReportContext):

  def provide(): List[SyntheticDecoration] =
    Collector
      .result()
      .flatten
      .sortWith((n1, n2) =>
        if n1.start() == n2.start() then n1.end() < n2.end()
        else n1.start() < n2.start()
      )

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
      if isInScope(tpe)
      then tpe
      else tpe.metalsDealias

    def printType(tpe: Type) =
      printer.tpe(optDealias(tpe))

    override def collect(parent: Option[tpd.Tree])(
        tree: tpd.Tree,
        pos: SourcePosition,
        symbol: Option[Symbol],
    ): Option[SyntheticDecoration] =
      val sym = symbol.fold(tree.symbol)(identity)
      if sym == NoSymbol then None
      else
        val tpe = printType(sym.info)
        val kind = inlayHintKind(sym)
        Some(Decoration(pos.start, pos.end, tpe, kind))

    def inlayHintKind(sym: Symbol): Int =
      if sym.is(Flags.Given) || sym.is(Flags.Implicit) then 2
      else 1
  end Collector

end PcSyntheticDecorationsProvider
