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
) extends ExtractMethodUtils:

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

    def valsOnPath(ts: List[tpd.Tree]): List[(String, String)] =
      ts.flatMap(
        _ match
          case ValDef(name, tpt, _) =>
            List((name.toString, printer.tpe(tpt.tpe)))
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

    def localRefs(ts: List[tpd.Tree]): Set[String] =
      val names = Set.newBuilder[String]
      def collectNames(defns: Set[String], tree: tpd.Tree): Set[String] =
        tree match
          case Ident(name) =>
            if !defns(name.toString()) then names += name.toString()
            defns
          case Select(qualifier, name) =>
            if !defns(name.toString()) then names += name.toString()
            defns
          case ValDef(name, _, _) =>
            defns + name.toString()
          case _ => defns

      val traverser = new DeepFolder[Set[String]](collectNames)
      ts.foldLeft(Set.empty[String])(traverser(_, _))
      names.result()
    end localRefs

    def defsOnPath(ts: List[tpd.Tree]): Set[String] =
      ts.flatMap(
        _ match
          case DefDef(name, _, _, _) => Seq(name.toString())
          case b @ Block(stats, expr) => defsOnPath(stats :+ expr)
          case t @ Template(_, _, _, _) =>
            defsOnPath(t.body)
          case _ => Nil
      ).toSet

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
        val (methodParams, applParams) =
          asParams(valsOnPath(shortenedPath).distinct, localRefs(extracted))
        val applType = printer.tpe(appl.tpe.widenUnion)
        val name = genName(defsOnPath(path), "newMethod")
        val text = params.text()
        val newIndent = stat.startPos.startColumnPadding
        val oldIndentLen = head.startPos.startColumnPadding.length()
        val toExtract =
          textToExtract(text, pos.start, pos.end, newIndent, oldIndentLen)
        val defText =
          if extracted.length > 1 then
            s"def $name($methodParams): $applType =\n${toExtract}\n\n$newIndent"
          else
            s"def $name($methodParams): $applType =\n${toExtract}\n\n$newIndent"
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
