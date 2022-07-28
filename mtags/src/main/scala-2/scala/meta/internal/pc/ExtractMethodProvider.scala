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

    def valsOnPath(ts: List[Tree]): List[(String, String)] = {
      ts.flatMap(t =>
        t match {
          case Block(stats, expr) => valsOnPath(stats :+ expr)
          case Template(_, _, body) =>
            valsOnPath(body)
          case df: DefDef =>
            valsOnPath(df.vparamss.flatten)
          case ValDef(_, name, tpt, _) if tpt.tpe != null =>
            List((name.toString(), prettyType(tpt.tpe)))
          case ValDef(_, name, Ident(tpe), _) =>
            List((name.toString(), tpe.toString()))
          case _ => Nil
        }
      )
    }

    def defsOnPath(ts: List[Tree]): Set[String] = {
      ts.flatMap(t =>
        t match {
          case Block(stats, expr) => defsOnPath(stats :+ expr)
          case Template(_, _, body) => defsOnPath(body)
          case DefDef(_, name, _, _, _, _) =>
            Seq(name.toString)
          case _ => Nil
        }
      ).toSet
    }

    def localRefs(ts: List[Tree]): Set[String] = {
      val names = Set.newBuilder[String]
      def traverse(defns: Set[String], tree: Tree): Set[String] =
        tree match {
          case Ident(name) =>
            if (!defns(name.toString)) names += name.toString
            defns
          case Select(qualifier, name) =>
            if (!defns(name.toString)) names += name.toString
            traverse(defns, qualifier)
            defns
          case ValDef(_, name, _, rhs) =>
            traverse(defns, rhs)
            defns + name.toString
          case _ =>
            tree.children.foldLeft(defns)(traverse(_, _))
            defns
        }
      ts.foldLeft(Set.empty[String])(traverse(_, _))
      names.result()
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
        val (methodParams, applParams) =
          asParams(valsOnPath(shortenedPath).distinct, localRefs(extracted))
        val newAppl = typedTreeAt(appl.pos)
        val applType =
          if (newAppl.tpe != null) s": ${prettyType(newAppl.tpe.widen)}" else ""
        val name = genName(defsOnPath(path), "newMethod")
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
            s"def $name($methodParams)$applType = {\n${toExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name($methodParams)$applType =\n${toExtract}\n\n$newIndent"
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
