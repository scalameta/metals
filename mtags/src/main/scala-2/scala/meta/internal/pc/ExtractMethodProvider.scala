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
    val context = doLocateImportContext(pos)
    val re: scala.collection.Map[Symbol, Name] = renamedSymbols(context)
    typedTreeAt(pos)
    val history = new ShortenedNames(
      lookupSymbol = name =>
        context.lookupSymbol(name, sym => !sym.isStale) :: Nil,
      config = renameConfig,
      renames = re
    )
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

    def localRefs(ts: List[Tree]): Set[Name] = {
      def traverse(names: Set[Name], tree: Tree): Set[Name] =
        tree match {
          case Ident(name) =>
            names + name
          case Select(qualifier, name) =>
            traverse(names, qualifier) + name
          case _ =>
            tree.children.foldLeft(names)(traverse(_, _))
        }
      ts.foldLeft(Set.empty[Name])(traverse(_, _))
    }

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
        val scopeSymbols =
          metalsScopeMembers(pos).map(_.sym).filter(_.pos.isDefined)
        val noLongerAvailable = scopeSymbols
          .filter(sym => stat.pos.encloses(sym.pos) && !range.encloses(sym.pos))
        val names = localRefs(extracted)
        val paramsToExtract = noLongerAvailable
          .filter(sym => names.contains(sym.name))
          .map(sym => (sym.name, sym.info))
          .sortBy(_._1.decoded)
        val newExpr = typedTreeAt(expr.pos)
        val exprType =
          if (newExpr.tpe != null) s": ${prettyType(newExpr.tpe.widen)}" else ""
        val name = genName(scopeSymbols.map(_.decodedName).toSet, "newMethod")
        val methodParams = paramsToExtract
          .map { case (name, tpe) => s"${name.decoded}: ${prettyType(tpe)}" }
          .mkString(", ")
        val typeParams = paramsToExtract
          .map(_._2.typeSymbol)
          .filter(noLongerAvailable.contains(_))
          .map(_.name.decoded) match {
          case Nil => ""
          case params => params.mkString("[", ", ", "]")
        }
        val exprParams = paramsToExtract.map(_._1.decoded).mkString(", ")
        val indent = stat.pos.column - (stat.pos.point - stat.pos.start) - 1
        val blank = text(stat.pos.start - indent).toString()
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
            s"def $name$typeParams($methodParams)$exprType = {\n${toExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name$typeParams($methodParams)$exprType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($exprParams)"
        List(
          new l.TextEdit(
            head.pos.withEnd(expr.pos.end).toLSP,
            replacedText
          ),
          new l.TextEdit(
            stat.pos.focusStart.toLSP,
            defText
          )
        )
      }
    edits.getOrElse(Nil)
  }
}
