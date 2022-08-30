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

import dotty.tools.dotc.core.Flags.Method
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
) extends ExtractMethodUtils:

  def extractMethod(): List[TextEdit] =
    val text = range.text()
    val uri = range.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos =
      val rangePos = driver.sourcePosition(range)
      rangePos.withEnd(rangePos.start)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
    given locatedCtx: Context =
      val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
      MetalsInteractive.contextOfPath(path)(using newctx)
    val indexedCtx = IndexedContext(locatedCtx)

    def extractFromBlock(t: tpd.Tree): List[tpd.Tree] =
      t match
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat => range.encloses(stat.sourcePos))
        case temp: Template[?] =>
          temp.body.filter(stat => range.encloses(stat.sourcePos))
        case other => List(other)

    def localRefs(
        ts: List[tpd.Tree],
        defnPos: SourcePosition,
        extractedPos: SourcePosition,
    ) =
      def nonAvailable(sym: Symbol): Boolean =
        val symPos = sym.sourcePos
        symPos.exists && defnPos.contains(symPos) && !extractedPos
          .contains(symPos)
      def collectNames(symbols: Set[Symbol], tree: tpd.Tree): Set[Symbol] =
        tree match
          case id @ Ident(_) =>
            if nonAvailable(id.symbol) && id.symbol.isTerm && !id.symbol.is(
                Method
              )
            then symbols + id.symbol
            else symbols
          case _ => symbols

      val traverser = new DeepFolder[Set[Symbol]](collectNames)
      val methodParams = ts
        .foldLeft(Set.empty[Symbol])(traverser(_, _))
        .toList
        .sortBy(_.decodedName)
      val typeParams =
        methodParams
          .map(_.info.typeSymbol)
          .filter(t => nonAvailable(t))
          .distinct
      (methodParams, typeParams)
    end localRefs
    val edits =
      for
        enclosing <- path.find(src => src.sourcePos.encloses(range))
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        expr <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src => extractionPos.offset() <= src.sourcePos.start)
        stat = shortenedPath.lastOption.getOrElse(head)
      yield
        val defnPos = stat.sourcePos
        val extractedPos = head.sourcePos.withEnd(expr.sourcePos.end)
        val exprType = expr.tpe.widenUnion.show
        val name =
          genName(indexedCtx.scopeSymbols.map(_.decodedName).toSet, "newMethod")
        val (methodParams, typeParams) =
          localRefs(extracted, stat.sourcePos, extractedPos)
        val methodParamsText = methodParams
          .map(sym => s"${sym.decodedName}: ${sym.info.show}")
          .mkString(", ")
        val typeParamsText = typeParams
          .map(_.decodedName) match
          case Nil => ""
          case params => params.mkString("[", ", ", "]")
        val exprParamsText = methodParams.map(_.decodedName).mkString(", ")
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
          s"def $name$typeParamsText($methodParamsText): $exprType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($exprParamsText)"
        List(
          new l.TextEdit(
            toLSP(extractedPos),
            replacedText,
          ),
          new l.TextEdit(
            toLSP(defnPos.startPos),
            defText,
          ),
        )

    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
