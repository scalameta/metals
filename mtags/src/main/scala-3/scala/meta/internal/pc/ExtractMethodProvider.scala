package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta as m

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.MetalsInteractive.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.internal.pc.printer.MetalsPrinter.IncludeDefaultParam
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.Trees.*
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.DeepFolder
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.MethodType
import dotty.tools.dotc.core.Types.PolyType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j as l}

final class ExtractMethodProvider(
    range: RangeParams,
    extractionPos: OffsetParams,
    driver: InteractiveDriver,
    search: SymbolSearch,
    noIndent: Boolean,
) extends ExtractMethodUtils:

  def extractMethod(): List[TextEdit] =
    val text = range.text()
    val uri = range.uri
    val filePath = Paths.get(uri)
    val source = SourceFile.virtual(filePath.toString, text)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(range).startPos
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
    given locatedCtx: Context =
      val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
      MetalsInteractive.contextOfPath(path)(using newctx)
    val indexedCtx = IndexedContext(locatedCtx)
    val printer =
      MetalsPrinter.standard(indexedCtx, search, IncludeDefaultParam.Never)
    def prettyPrint(tpe: Type) =
      def prettyPrintReturnType(tpe: Type): String =
        tpe match
          case mt: (MethodType | PolyType) =>
            prettyPrintReturnType(tpe.resultType)
          case tpe => printer.tpe(tpe)
      def printParams(params: List[Type]) =
        params match
          case p :: Nil => prettyPrintReturnType(p)
          case _ => s"(${params.map(prettyPrintReturnType).mkString(", ")})"

      if tpe.paramInfoss.isEmpty
      then prettyPrintReturnType(tpe)
      else
        val params = tpe.paramInfoss.map(printParams).mkString(" => ")
        s"$params => ${prettyPrintReturnType(tpe)}"
    end prettyPrint

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
    ): (List[Symbol], List[Symbol]) =
      def nonAvailable(sym: Symbol): Boolean =
        val symPos = sym.sourcePos
        symPos.exists && defnPos.contains(symPos) && !extractedPos
          .contains(symPos)
      def collectNames(symbols: Set[Symbol], tree: tpd.Tree): Set[Symbol] =
        tree match
          case id @ Ident(_) =>
            val sym = id.symbol
            if nonAvailable(sym) && (sym.isTerm || sym.isTypeParam)
            then symbols + sym
            else symbols
          case _ => symbols

      val traverser = new DeepFolder[Set[Symbol]](collectNames)
      val allSymbols = ts
        .foldLeft(Set.empty[Symbol])(traverser(_, _))

      val methodParams = allSymbols.toList.filter(_.isTerm)
      val methodParamTypes = methodParams
        .flatMap(p => p :: p.paramSymss.flatten)
        .map(_.info.typeSymbol)
        .filter(tp => nonAvailable(tp) && tp.isTypeParam)
        .distinct
      // Type parameter can be a type of one of the parameters or a type parameter in extracted code
      val typeParams =
        allSymbols.filter(_.isTypeParam) ++ methodParamTypes

      (
        methodParams.sortBy(_.decodedName),
        typeParams.toList.sortBy(_.decodedName),
      )
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
        val exprType = prettyPrint(expr.tpe.widen)
        val name =
          genName(indexedCtx.scopeSymbols.map(_.decodedName).toSet, "newMethod")
        val (methodParams, typeParams) =
          localRefs(extracted, stat.sourcePos, extractedPos)
        val methodParamsText = methodParams
          .map(sym => s"${sym.decodedName}: ${prettyPrint(sym.info)}")
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
            range.text(),
            head.startPos.start,
            expr.endPos.end,
            newIndent,
            oldIndentLen,
          )
        val (obracket, cbracket) =
          if noIndent && extracted.length > 1 then (" {", s"$newIndent}")
          else ("", "")
        val defText =
          s"def $name$typeParamsText($methodParamsText): $exprType =$obracket\n${toExtract}\n$cbracket\n$newIndent"
        val replacedText = s"$name($exprParamsText)"
        List(
          new l.TextEdit(
            extractedPos.toLsp,
            replacedText,
          ),
          new l.TextEdit(
            defnPos.startPos.toLsp,
            defText,
          ),
        )

    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
