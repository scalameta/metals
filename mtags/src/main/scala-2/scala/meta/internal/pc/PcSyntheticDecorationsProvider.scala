package scala.meta.internal.pc

import scala.collection.JavaConverters._

import scala.meta.pc.SyntheticDecoration
import scala.meta.pc.SyntheticDecorationsParams

import org.eclipse.lsp4j

final class PcSyntheticDecorationsProvider(
    protected val cp: MetalsGlobal, // compiler
    val params: SyntheticDecorationsParams
) {

  def provide(): List[SyntheticDecoration] = {
    Collector
      .resultAllOccurences(includeSynthetics = true)()
      .flatten
      .toList
  }

  // Initialize Tree
  object Collector
      extends PcCollector[Option[SyntheticDecoration]](cp, params) {

    import compiler._
    val withoutTypes: Set[lsp4j.Range] = params.declsWithoutTypesRanges().asScala.toSet

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
                val (lastArgPos, _) = adjust(args.lastOption.fold(pos)(_.pos))
                Some(
                  Decoration(
                    lastArgPos.toLsp,
                    sym.decodedName,
                    DecorationKind.ImplicitConversion
                  )
                )
              } else None
            case ap @ Apply(_, args)
                if args.exists(_.pos == pos) && pos.isOffset =>
              if (params.implicitParameters) {
                val (adjustedPos, _) = adjust(ap.pos.focusEnd)
                Some(
                  Decoration(
                    adjustedPos.toLsp,
                    sym.decodedName,
                    DecorationKind.ImplicitParameter
                  )
                )
              } else None

            case TypeApply(sel: Select, _) if syntheticTupleApply(sel) =>
              None
            case ta @ TypeApply(fun, args)
                if args.exists(
                  _.pos == pos
                ) && pos.isOffset && ta.pos.isRange =>
              if (params.inferredTypes) {
                val tpe = tree.tpe.widen.finalResultType
                val label = toLabel(tpe, pos)
                val adjustedPos = fun.pos.focusEnd
                Some(
                  Decoration(
                    adjustedPos.toLsp,
                    label,
                    DecorationKind.TypeParameter
                  )
                )
              } else None

          }
          .getOrElse {
            val adjustedPos = adjust(pos)._1.toLsp
            if (pos.isRange && withoutTypes(adjustedPos)) {
              val tpe = sym.tpe.widen.finalResultType
              val label = toLabel(tpe, pos)
              val kind = DecorationKind.InferredType
              Some(
                Decoration(adjustedPos, label, kind)
              )
            } else None
          }
    }

    private def syntheticTupleApply(sel: Select): Boolean = {
      if (compiler.definitions.isTupleType(sel.tpe.finalResultType)) {
        sel match {
          case Select(tupleClass: Select, _)
              if tupleClass.pos.isRange &&
                tupleClass.name.startsWith("Tuple") =>
            val pos = tupleClass.pos
            !text.slice(pos.start, pos.end).mkString.startsWith("Tuple")
          case _ => true
        }
      } else false
    }

    private def toLabel(
        tpe: Type,
        pos: Position
    ): String = {
      val context: Context = doLocateImportContext(pos)
      val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
      val history = new ShortenedNames(
        lookupSymbol = name =>
          context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
        config = renameConfig,
        renames = re
      )
      metalsToLongString(tpe, history)
    }

  }
}
