package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.XtensionLspRange
import scala.meta.internal.pc.ExtractMethodUtils
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    range: l.Range,
    defnRange: l.Range
) extends ExtractMethodUtils {
  import compiler._
  def extractMethod: List[l.TextEdit] = {
    val unit = addCompilationUnit(
      code = params.text(),
      filename = params.uri().toString(),
      cursor = None
    )
    val pos = unit.position(params.offset())
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
          (stats :+ expr).filter(stat => range.encloses(stat.pos.toLSP))
        case temp: Template =>
          temp.body.filter(stat => range.encloses(stat.pos.toLSP))
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
        enclosing <- path.find(_.pos.toLSP.encloses(range))
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        appl <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src => defnRange.encloses(src.pos.toLSP))
        stat = shortenedPath.lastOption.getOrElse(head)
      } yield {
        val scopeSymbols =
          metalsScopeMembers(pos).map(_.sym).filter(_.pos.isDefined)
        val noLongerAvailable = scopeSymbols
          .filter(sym =>
            defnRange.encloses(sym.pos.toLSP) && !range.encloses(sym.pos.toLSP)
          )
        val names = localRefs(extracted)
        val paramsToExtract = noLongerAvailable
          .filter(sym => names.contains(sym.name))
          .map(sym => (sym.name, sym.info))
          .sortBy(_._1.decoded)
        val newAppl = typedTreeAt(appl.pos)
        val applType =
          if (newAppl.tpe != null) s": ${prettyType(newAppl.tpe.widen)}" else ""
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
        val applParams = paramsToExtract.map(_._1.decoded).mkString(", ")
        val text = params.text()
        val indent = stat.pos.column - (stat.pos.point - stat.pos.start) - 1
        val blank = text(stat.pos.start - indent).toString()
        val newIndent = blank * indent
        val oldIndentLen =
          head.pos.column - (head.pos.point - head.pos.start) - 1
        val toExtract = textToExtract(
          text,
          head.pos.start,
          appl.pos.end,
          newIndent,
          oldIndentLen
        )
        val defText =
          if (extracted.length > 1)
            s"def $name$typeParams($methodParams)$applType = {\n${toExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name$typeParams($methodParams)$applType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($applParams)"
        List(
          new l.TextEdit(
            range,
            replacedText
          ),
          new l.TextEdit(
            new l.Range(defnRange.getStart(), defnRange.getStart()),
            defText
          )
        )
      }
    edits.getOrElse(Nil)
  }
}
