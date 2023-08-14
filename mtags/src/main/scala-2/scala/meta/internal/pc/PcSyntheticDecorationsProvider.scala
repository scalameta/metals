package scala.meta.internal.pc

import scala.collection.mutable.ListBuffer

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.RangeParams
import scala.meta.pc.SyntheticDecoration

final class PcSyntheticDecorationsProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: RangeParams
) {

  def provide(): List[SyntheticDecoration] = {
    val trees = Collector.treesInRange(params)
    Collector
      .resultAllOccurences(includeSynthetics = true)(trees)
      .flatten
      .toList
      .sortWith((n1, n2) => n1.range().lt(n2.range()))
  }

  // Initialize Tree
  object Collector
      extends PcCollector[Option[SyntheticDecoration]](cp, params) {

    import compiler._
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )

    def printType(tpe: Type): String =
      metalsToLongString(tpe.widen.finalResultType, history)

    override def collect(
        parent: Option[compiler.Tree]
    )(
        tree: compiler.Tree,
        pos: compiler.Position,
        symbol: Option[compiler.Symbol]
    ): Option[SyntheticDecoration] = {
      val sym = symbol.fold(tree.symbol)(identity)
      if (sym == null) None
      else 
        parent
          .collectFirst {
            case Apply(fun, args) if fun.pos == pos && pos.isOffset =>
              val lastArgPos = args.lastOption.fold(pos)(_.pos)
              Decoration(
                lastArgPos.toLsp,
                sym.decodedName,
                DecorationKind.ImplicitConversion,
                Some(semanticdbSymbol(sym))
              )
            case ap @ Apply(_, args)
                if args.exists(_.pos == pos) && pos.isOffset =>
              Decoration(
                ap.pos.focusEnd.toLsp,
                sym.decodedName,
                DecorationKind.ImplicitParameter,
                Some(semanticdbSymbol(sym))
              )
            case ta @ TypeApply(fun, args)
                if args.exists(_.pos == pos) && pos.isOffset && ta.pos.isRange &&
                  !compiler.definitions.isTupleType(fun.tpe.finalResultType) =>
              val parts = partsFromType(tree.tpe.widen.finalResultType)
              val labelParts = makeLabelParts(parts, tree.tpe)
              Decoration(
                fun.pos.focusEnd.toLsp,
                labelParts,
                DecorationKind.TypeParameter
              )
          }
          .orElse {
            if (pos.isRange) {

              val parts = partsFromType(sym.tpe.widen.finalResultType)
              val labelParts = makeLabelParts(parts, sym.tpe)
              val kind = DecorationKind.InferredType
              Some(
                Decoration(pos.toLsp, labelParts, kind)
              )
            } else None
          }
    }

    def partsFromType(tpe: Type): List[TypeWithName] = {
      tpe
        .collect {
          case t: TypeRef if t.typeSymbol != NoSymbol => TypeWithName(t)
        }
        .distinctBy(_.name)
    }
    
    def makeLabelParts(
        parts: List[TypeWithName],
        tpe: Type
    ): List[LabelPart] = {
      val buffer = ListBuffer.empty[LabelPart]
      var current = 0
      val tpeStr = printType(tpe)
      parts
        .flatMap { tp =>
          allIndexesWhere(tp.name, tpeStr).map((_, tp))
        }
        .sortWith { case ((idx1, tp1), (idx2, tp2)) =>
          if (idx1 == idx2) tp1.name.length > tp2.name.length else idx1 < idx2
        }
        .foreach { case (index, tp) =>
          if (index >= current) {
            buffer += labelPart(tpeStr.substring(current, index))
            buffer += labelPart(tp.name, Some(tp.tpe.typeSymbol))
            current = index + tp.name.length
          }

        }
      buffer += labelPart(tpeStr.substring(current, tpeStr.length))
      buffer.toList.filter(!_.label.isEmpty())
    }

    def labelPart(
        label: String,
        symbol: Option[Symbol] = None
    ): LabelPart = {
      val symbolStr = symbol.map(semanticdbSymbol).getOrElse("")
      LabelPart(label, symbolStr)
    }

    def allIndexesWhere(
        str: String,
        in: String
    ): List[Int] = {
      val buffer = ListBuffer.empty[Int]
      var index = in.indexOf(str)
      while (index >= 0) {
        buffer += index
        index = in.indexOf(str, index + 1)
      }
      buffer.toList
    }

    case class TypeWithName(tpe: Type, name: String)
    object TypeWithName {
      def apply(tpe: Type): TypeWithName =
        TypeWithName(tpe, tpe.typeSymbol.decodedName)
    }
  }
}
