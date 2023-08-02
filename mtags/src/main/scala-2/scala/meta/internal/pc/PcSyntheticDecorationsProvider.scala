package scala.meta.internal.pc

import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.VirtualFileParams
import scala.meta.internal.mtags.MtagsEnrichments._

final class PcSyntheticDecorationsProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: VirtualFileParams
) {

  def provide(): List[SyntheticDecoration] =
    Collector
      .result()
      .flatten
      .sortWith((n1, n2) => n1.range().lt(n2.range()))

  // Initialize Tree
  object Collector
      extends PcCollector[Option[SyntheticDecoration]](cp, params) {

    import compiler._
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )

    def printType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: compiler.Position,
        symbol: Option[compiler.Symbol]
    ): Option[SyntheticDecoration] = {
      val sym = symbol.fold(tree.symbol)(identity)
      if (sym == null || sym == compiler.NoSymbol) {
        None
      } else {
        val tpe = printType(sym.tpe)
        val kind = inlayHintKind(sym)
        Some(
          Decoration(pos.toLsp, tpe, kind)
        )
      }
    }

    def inlayHintKind(sym: compiler.Symbol): Int = {
      if (sym.isImplicit) 2
      else 1
    }
  }
}
