package scala.meta.internal.pc

import java.nio.file.Paths

import scala.collection.mutable
import scala.collection.mutable.Builder
import scala.collection.mutable.ListBuffer
import scala.meta as m

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.MetalsInteractive.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.RangeParams

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.DeepFolder
import dotty.tools.dotc.ast.tpd.TreeTraverser
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j as l}

final class ExtractMethodProvider(
    range: RangeParams,
    extractionPos: OffsetParams,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig,
) extends ExtractMethodUtils:

  def extractMethod(): List[TextEdit] =
    val text = range.text()
    val params = range.toOffset
    val uri = params.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
    given locatedCtx: Context = driver.localContext(params)
    val indexedCtx = IndexedContext(locatedCtx)
    val rangeSourcePos =
      SourcePosition(source, Span(range.offset(), range.endOffset()))
    val extractionSourcePos =
      SourcePosition(source, Span(extractionPos.offset()))

    def extractFromBlock(t: tpd.Tree): List[tpd.Tree] =
      t match
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat =>
            rangeSourcePos.encloses(stat.sourcePos)
          )
        case temp: Template[?] =>
          temp.body.filter(stat => rangeSourcePos.encloses(stat.sourcePos))
        case other => List(other)

    def localRefs(ts: List[tpd.Tree]): Set[Name] =
      def collectNames(names: Set[Name], tree: tpd.Tree): Set[Name] =
        tree match
          case Ident(name) =>
            names + name
          case Select(qualifier, name) =>
            names + name
          case _ => names

      val traverser = new DeepFolder[Set[Name]](collectNames)
      ts.foldLeft(Set.empty[Name])(traverser(_, _))
    end localRefs

    val edits =
      for
        enclosing <- path.find(src => src.sourcePos.encloses(rangeSourcePos))
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        expr <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src =>
            extractionSourcePos.start <= src.sourcePos.start
          )
        stat = shortenedPath.lastOption.getOrElse(head)
      yield
        val exprType = expr.tpe.widenUnion.show
        val scopeSymbols = indexedCtx.scopeSymbols
          .filter(_.isDefinedInCurrentRun)
        val noLongerAvailable = scopeSymbols
          .filter(s =>
            stat.sourcePos.encloses(s.sourcePos) && !rangeSourcePos.encloses(
              s.sourcePos
            )
          )
        val names = localRefs(extracted)
        val name = genName(scopeSymbols.map(_.decodedName).toSet, "newMethod")
        val paramsToExtract = noLongerAvailable
          .filter(sym => names.contains(sym.name))
          .map(sym => (sym.name, sym.info))
          .sortBy(_._1.decoded)
        val methodParams = paramsToExtract
          .map { case (name, tpe) => s"${name.decoded}: ${tpe.show}" }
          .mkString(", ")
        val typeParams = paramsToExtract
          .map(_._2.typeSymbol)
          .filter(noLongerAvailable.contains(_))
          .map(_.name.show) match
          case Nil => ""
          case params => params.mkString("[", ", ", "]")
        val exprParams = paramsToExtract.map(_._1.decoded).mkString(", ")
        val newIndent = stat.startPos.startColumnPadding
        val oldIndentLen = head.startPos.startColumnPadding.length()
        val toExtract =
          textToExtract(
            text,
            head.startPos.start,
            expr.endPos.end,
            newIndent,
            oldIndentLen,
          )
        val defText =
          if extracted.length > 1 then
            s"def $name$typeParams($methodParams): $exprType =\n${toExtract}\n\n$newIndent"
          else
            s"def $name$typeParams($methodParams): $exprType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($exprParams)"
        List(
          new l.TextEdit(
            toLSP(rangeSourcePos),
            replacedText,
          ),
          new l.TextEdit(
            toLSP(extractionSourcePos),
            defText,
          ),
        )

    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
