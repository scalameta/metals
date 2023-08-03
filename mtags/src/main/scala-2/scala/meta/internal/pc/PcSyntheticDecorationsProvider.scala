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
      .resultAllOccurences(includeSynthetics = true)
      .flatten
      .toList
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
      parent
        .collectFirst {
          case Apply(fun, args) if fun.pos == pos && pos.isOffset =>
            val lastArgPos = args.lastOption.fold(pos)(_.pos)
            Decoration(
              lastArgPos.toLsp,
              sym.decodedName,
              DecorationKind.ImplicitConversion
            )
          case ap @ Apply(_, args)
              if args.exists(_.pos == pos) && pos.isOffset =>
            Decoration(
              ap.pos.focusEnd.toLsp,
              sym.decodedName,
              DecorationKind.ImplicitParameter
            )
          case TypeApply(fun, args)
              if args.exists(_.pos == pos) && pos.isOffset &&
                !compiler.definitions.isTupleType(fun.tpe.finalResultType) =>
            Decoration(
              fun.pos.focusEnd.toLsp,
              printType(tree.tpe),
              DecorationKind.TypeParameter
            )
        }
        .orElse {
          val tpe = printType(sym.tpe)
          val kind = DecorationKind.InferredType
          Some(
            Decoration(pos.toLsp, tpe, kind)
          )
        }
    }
  }
}
