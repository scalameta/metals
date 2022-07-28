package scala.meta.internal.pc

import scala.meta.internal.mtags.MtagsEnrichments.XtensionLspRange
import scala.meta.pc.OffsetParams

import org.eclipse.{lsp4j => l}

final class ExtractMethodProvider(
    val compiler: MetalsGlobal,
    params: OffsetParams,
    range: l.Range,
    defnRange: l.Range
) {
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

    def valsOnPath(ts: List[Tree]): List[(TermName, String)] = {
      ts.flatMap(t =>
        t match {
          case Block(stats, expr) => valsOnPath(stats :+ expr)
          case Template(_, _, body) => valsOnPath(body)
          case ValDef(_, name, tpt, _) if tpt.tpe != null =>
            List((name, prettyType(tpt.tpe)))
          case _ => Nil
        }
      )
    }

    def genName(path: List[Tree]): String = {
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
      val usedNames = defsOnPath(path)
      if (!usedNames("newMethod")) "newMethod"
      else {
        var i = 0
        while (usedNames(s"newMethod$i")) {
          i += 1
        }
        s"newMethod$i"
      }
    }

    def localRefs(ts: List[Tree]): Set[TermName] = {
      val names = Set.newBuilder[TermName]
      def traverse(defns: Set[TermName], tree: Tree): Set[TermName] =
        tree match {
          case Ident(name) =>
            if (!defns(name.toTermName)) names += name.toTermName
            defns
          case Select(qualifier, name) =>
            if (!defns(name.toTermName)) names += name.toTermName
            traverse(defns, qualifier)
            defns
          case ValDef(_, name, _, rhs) =>
            traverse(defns, rhs)
            defns + name.toTermName
          case _ =>
            tree.children.foldLeft(defns)(traverse(_, _))
            defns
        }
      ts.foldLeft(Set.empty[TermName])(traverse(_, _))
      val res = names.result()
      res
    }

    def adjustIndent(
        line: String,
        newIndent: String,
        oldIndent: Int
    ): String = {
      var i = 0
      val additional = if (newIndent.indexOf("\t") != -1) "\t" else "  "
      while ((line(i) == ' ' || line(i) == '\t') && i < oldIndent) {
        i += 1
      }
      newIndent + additional + line.drop(i)
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
        val noLongerAvailable = valsOnPath(shortenedPath)
        val refsExtract = localRefs(extracted)
        val withType =
          noLongerAvailable.filter { case (key, _) =>
            refsExtract.contains(key)
          }.sorted
        val typs = withType
          .map { case (name, tpe) => s"$name: $tpe" }
          .mkString(", ")
        val newAppl = typedTreeAt(appl.pos)
        val applType =
          if (newAppl.tpe != null) s": ${prettyType(newAppl.tpe.widen)}" else ""
        val applParams = withType.map(_._1).mkString(", ")
        val name = genName(path)
        val text = params.text()
        val indent = stat.pos.column - (stat.pos.point - stat.pos.start) - 1
        val blank =
          if (text(stat.pos.start - indent) == '\t') "\t"
          else " "
        val newIndent = blank * indent
        val oldIndent = head.pos.column - (head.pos.point - head.pos.start) - 1
        val textToExtract = text
          .slice(head.pos.start, appl.pos.end)
          .split("\n")
          .map(adjustIndent(_, newIndent, oldIndent))
          .mkString("\n")
        val defText =
          if (extracted.length > 1)
            s"def $name($typs)$applType = {\n${textToExtract}\n${newIndent}}\n$newIndent"
          else
            s"def $name($typs)$applType =\n${textToExtract}\n\n$newIndent"
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
