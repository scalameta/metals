package scala.meta.internal.pc

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

import org.eclipse.lsp4j

final class PcSyntheticDecorationsProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: SyntheticDecorationsParams
) {

  def provide(): List[SyntheticDecoration] = {
    val trees = Collector.treesInRange(params)
    Collector
      .resultAllOccurences(includeSynthetics = true)(trees)
      .flatten
      .toList
  }

  // Initialize Tree
  object Collector
      extends PcCollector[Option[SyntheticDecoration]](cp, params) {

    import compiler._
    val context: Context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)

    def printTypeWithContext(tpe: Type, pos: Position): String = {
      val context: Context = doLocateImportContext(pos)
      val history = new ShortenedNames(
        lookupSymbol = name =>
          context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
        config = renameConfig,
        renames = re
      )
      metalsToLongString(tpe.widen.finalResultType, history)
    }

    val withoutTypes: Set[lsp4j.Range] = params.withoutTypes().asScala.toSet

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
              if (params.implicitConversions) {
                val lastArgPos = args.lastOption.fold(pos)(_.pos)
                Some(
                  Decoration(
                    lastArgPos.toLsp,
                    sym.decodedName,
                    DecorationKind.ImplicitConversion,
                    Some(semanticdbSymbol(sym))
                  )
                )
              } else None
            case ap @ Apply(_, args)
                if args.exists(_.pos == pos) && pos.isOffset =>
              if (params.implicitParameters) {
                Some(
                  Decoration(
                    ap.pos.focusEnd.toLsp,
                    sym.decodedName,
                    DecorationKind.ImplicitParameter,
                    Some(semanticdbSymbol(sym))
                  )
                )
              } else None
            case ta @ TypeApply(fun, args)
                if args.exists(
                  _.pos == pos
                ) && pos.isOffset && ta.pos.isRange &&
                  !compiler.definitions.isTupleType(fun.tpe.finalResultType) =>
              if (params.inferredTypes) {
                val parts = partsFromType(tree.tpe.widen.finalResultType)
                val labelParts = makeLabelParts(parts, tree.tpe, pos)
                Some(
                  Decoration(
                    fun.pos.focusEnd.toLsp,
                    labelParts,
                    DecorationKind.TypeParameter
                  )
                )
              } else None

          }
          .getOrElse {
            if (pos.isRange && withoutTypes(pos.toLsp)) {
              val parts = partsFromType(sym.tpe.widen.finalResultType)
              val labelParts = makeLabelParts(parts, sym.tpe, pos)
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
          case t: TypeRef if t.sym != NoSymbol =>
            TypeWithName(t.sym.decodedName, semanticdbSymbol(t.sym))
          case SingleType(_, sym) if sym != NoSymbol =>
            TypeWithName(sym.decodedName, semanticdbSymbol(sym))
        }
    }

    def makeLabelParts(
        parts: List[TypeWithName],
        tpe: Type,
        pos: Position
    ): List[LabelPart] = {
      val buffer = ListBuffer.empty[LabelPart]
      var current = 0
      val tpeStr = printTypeWithContext(tpe, pos)
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
            buffer += labelPart(tp.name, Some(tp.semanticdbSymbol))
            current = index + tp.name.length
          }

        }
      buffer += labelPart(tpeStr.substring(current, tpeStr.length))
      buffer.toList.filter(!_.label.isEmpty())
    }

    def labelPart(
        label: String,
        symbol: Option[String] = None
    ): LabelPart = {
      val symbolStr = symbol.getOrElse("")
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

    case class TypeWithName(name: String, semanticdbSymbol: String)
  }
}
