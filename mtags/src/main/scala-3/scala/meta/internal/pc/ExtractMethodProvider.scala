package scala.meta.internal.pc

import java.nio.file.Paths

import scala.collection.mutable
import scala.collection.mutable.Builder
import scala.meta as m

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.DeepFolder
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j as l}

final class ExtractMethodProvider(
    params: OffsetParams,
    range: l.Range,
    defnRange: l.Range,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig,
    symbolSearch: SymbolSearch,
):

  def extractMethod(): List[TextEdit] =
    val uri = params.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, params.text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
    given locatedCtx: Context = driver.localContext(params)
    val indexedCtx = IndexedContext(locatedCtx)

    val printer = MetalsPrinter.standard(
      indexedCtx,
      symbolSearch,
      includeDefaultParam = MetalsPrinter.IncludeDefaultParam.ResolveLater,
    )

    def valsOnPath(ts: List[tpd.Tree]): List[(TermName, String)] =
      ts.flatMap(
        _ match
          case ValDef(name, tpt, _) => List((name, printer.tpe(tpt.tpe)))
          case df @ DefDef(_, _, _, _) =>
            valsOnPath(df.termParamss.flatten)
          case b @ Block(stats, expr) => valsOnPath(stats :+ expr)
          case t @ Template(_, _, _, _) =>
            valsOnPath(t.body)
          case _ => Nil
      )
    def extractFromBlock(t: tpd.Tree): List[tpd.Tree] =
      t match
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat => range.encloses(toLSP(stat.sourcePos)))
        case temp: Template[?] =>
          temp.body.filter(stat => range.encloses(toLSP(stat.sourcePos)))
        case other => List(other)

    def localRefs(ts: List[tpd.Tree]): Set[TermName] =
      val names = Set.newBuilder[TermName]
      def collectNames(defns: Set[TermName], tree: tpd.Tree): Set[TermName] =
        tree match
          case Ident(name) =>
            if !defns(name.toTermName) then names += name.toTermName
            defns
          case Select(qualifier, name) =>
            if !defns(name.toTermName) then names += name.toTermName
            defns
          case ValDef(name, _, _) =>
            defns + name.toTermName
          case _ => defns

      val traverser = new DeepFolder[Set[TermName]](collectNames)
      ts.foldLeft(Set.empty[TermName])(traverser(_, _))
      val res = names.result()
      res
    end localRefs

    def genName(path: List[tpd.Tree]): String =
      def defsOnPath(ts: List[tpd.Tree]): Set[String] =
        ts.flatMap(
          _ match
            case DefDef(name, _, _, _) => Seq(name.toString())
            case b @ Block(stats, expr) => defsOnPath(stats :+ expr)
            case t @ Template(_, _, _, _) =>
              defsOnPath(t.body)
            case _ => Nil
        ).toSet
      val usedNames = defsOnPath(path)
      if !usedNames("newMethod") then "newMethod"
      else
        var i = 0
        while usedNames(s"newMethod$i") do i += 1
        s"newMethod$i"
    end genName

    def adjustIndent(line: String, newIndent: String, oldIndent: Int): String =
      var i = 0
      val additional = if newIndent.indexOf("\t") != -1 then "\t" else "  "
      while (line(i) == ' ' || line(i) == '\t') && i < oldIndent do i += 1
      newIndent + additional + line.drop(i)

    val edits =
      for
        enclosing <- path.headOption
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        appl <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src => defnRange.encloses(toLSP(src.sourcePos)))
        stat = shortenedPath.lastOption.getOrElse(head)
      yield
        val noLongerAvailable = valsOnPath(shortenedPath)
        val refsExtract = localRefs(extracted)
        val withType =
          noLongerAvailable
            .filter((key, tp) => refsExtract.contains(key))
            .sorted
        val typs = withType
          .map((k, v) => s"$k: $v")
          .mkString(", ")
        val applType = printer.tpe(appl.tpe.widenUnion)
        val applParams = withType.map(_._1).mkString(", ")
        val name = genName(path)
        val text = params.text()
        val newIndent = stat.startPos.startColumnPadding
        val oldIndent = head.startPos.startColumnPadding.length()
        val textToExtract = text
          .slice(pos.start, pos.end)
          .split("\n")
          .map(adjustIndent(_, newIndent, oldIndent))
          .mkString("\n")
        val defText =
          if extracted.length > 1 then
            s"def $name($typs): $applType =\n${textToExtract}\n\n$newIndent"
          else s"def $name($typs): $applType =\n${textToExtract}\n\n$newIndent"
        val replacedText = s"$name($applParams)"
        List(
          new l.TextEdit(
            range,
            replacedText,
          ),
          new l.TextEdit(
            l.Range(defnRange.getStart(), defnRange.getStart()),
            defText,
          ),
        )

    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
