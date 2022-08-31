package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    range: RangeParams,
    extractionPos: OffsetParams
) extends ExtractMethodUtils {
  import compiler._
  def extractMethod: List[l.TextEdit] = {
    val text = range.text()
    val unit = addCompilationUnit(
      code = text,
      filename = range.uri.toString(),
      cursor = None
    )
    val pos = unit.position(range.offset)
    typedTreeAt(pos)
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
    val scopeSymbols =
      metalsScopeMembers(pos).map(_.sym).filter(_.pos.isDefined).toSet
    def prettyType(tpe: Type) =
      metalsToLongString(tpe.widen.finalResultType, history)

    def extractFromBlock(t: Tree): List[Tree] =
      t match {
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat => range.encloses(stat.pos))
        case temp: Template =>
          temp.body.filter(stat => range.encloses(stat.pos))
        case other => List(other)
      }

    def localRefs(
        ts: List[Tree],
        defnPos: Position,
        extractedPos: Position
    ) = {
      def nonAvailable(sym: Symbol): Boolean = {
        val symPos = sym.pos
        symPos.isDefined && defnPos.encloses(symPos) && !extractedPos.encloses(
          symPos
        )
      }
      def symFromIdent(id: Ident): Set[Symbol] = {
        val sym = id.symbol match {
          case _: NoSymbol =>
            context.lookupSymbol(id.name, s => s.isTerm) match {
              case LookupSucceeded(_, symbol) =>
                pprint.log(symbol)
                Set(symbol)
              case _ => Set.empty[Symbol]
            }
          case _ => Set(id.symbol).filter(s => s.isTerm && !s.isMethod)
        }
        sym.filter(nonAvailable(_))
      }

      def traverse(symbols: Set[Symbol], tree: Tree): Set[Symbol] =
        tree match {
          case id @ Ident(_) =>
            symbols ++ symFromIdent(id)
          case _ =>
            tree.children.foldLeft(symbols)(traverse(_, _))
        }
      val methodParams = ts
        .foldLeft(Set.empty[Symbol])(traverse(_, _))
        .toList
        .sortBy(_.decodedName)
      val typeParams =
        methodParams
          .map(_.info.typeSymbol)
          .filter(tp => nonAvailable(tp) && tp.isTypeParameterOrSkolem)
          .distinct
      pprint.log(methodParams.map(_.info.typeSymbol))
      pprint.log(methodParams.map(_.info.typeSymbol.isSkolem))

      (methodParams, typeParams)
    }
    pprint.log(scopeSymbols.filter(_.pos.isDefined))
    pprint.log(scopeSymbols.filter(_.isSkolem))

    val path = compiler.lastVisitedParentTrees
    val edits =
      for {
        enclosing <- path.find(src => src.pos.encloses(range))
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        expr <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src => extractionPos.offset() <= src.pos.start)
        stat = shortenedPath.lastOption.getOrElse(head)
      } yield {
        val defnPos = stat.pos
        val extractedPos = head.pos.withEnd(expr.pos.end)
        val (methodParams, typeParams) =
          localRefs(extracted, defnPos, extractedPos)
        val methodParamsText = methodParams
          .map(sym => s"${sym.decodedName}: ${prettyType(sym.info)}")
          .mkString(", ")
        val typeParamsText = typeParams
          .map(_.decodedName) match {
          case Nil => ""
          case params => params.mkString("[", ", ", "]")
        }
        val newExpr = typedTreeAt(expr.pos)
        val exprType =
          if (newExpr.tpe != null) s": ${prettyType(newExpr.tpe.widen)}" else ""
        val name = genName(scopeSymbols.map(_.decodedName), "newMethod")
        val exprParams = methodParams.map(_.decodedName).mkString(", ")
        val indent = defnPos.column - (defnPos.point - defnPos.start) - 1
        val blank = text(defnPos.start - indent).toString()
        val newIndent = blank * indent
        val oldIndentLen =
          head.pos.column - (head.pos.point - head.pos.start) - 1
        val toExtract = textToExtract(
          text,
          head.pos.start,
          expr.pos.end,
          newIndent,
          oldIndentLen
        )
        val defText =
          if (extracted.length > 1)
            s"def $name$typeParamsText($methodParamsText)$exprType = {\n${toExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name$typeParamsText($methodParamsText)$exprType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($exprParams)"
        List(
          new l.TextEdit(
            extractedPos.toLSP,
            replacedText
          ),
          new l.TextEdit(
            defnPos.focusStart.toLSP,
            defText
          )
        )
      }
    edits.getOrElse(Nil)
  }
}
