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
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j as l}

final class ExtractMethodProvider(
    params: OffsetParams,
    range: l.Range,
    extractionPos: l.Position,
    driver: InteractiveDriver,
    config: PresentationCompilerConfig,
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
    def extractFromBlock(t: tpd.Tree): List[tpd.Tree] =
      t match
        case Block(stats, expr) =>
          (stats :+ expr).filter(stat => range.encloses(toLSP(stat.sourcePos)))
        case temp: Template[?] =>
          temp.body.filter(stat => range.encloses(toLSP(stat.sourcePos)))
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
        enclosing <- path
          .dropWhile(!_.sourcePos.toLSP.encloses(range))
          .headOption
        extracted = extractFromBlock(enclosing)
        head <- extracted.headOption
        appl <- extracted.lastOption
        shortenedPath =
          path.takeWhile(src =>
            !toLSP(src.sourcePos).encloses(
              extractionPos
            ) || extractionPos == toLSP(src.sourcePos).getStart()
          )
        stat = shortenedPath.lastOption.getOrElse(head)
      yield
        val applType = appl.tpe.widenUnion.show
        val scopeSymbols = indexedCtx.scopeSymbols
          .filter(_.isDefinedInCurrentRun)
        val noLongerAvailable = scopeSymbols
          .filter(s =>
            toLSP(stat.sourcePos).encloses(s.sourcePos.toLSP) && !range
              .encloses(
                s.sourcePos.toLSP
              )
          )
        val names = localRefs(extracted)
        val name = genName(scopeSymbols.map(_.decodedName).toSet, "newMethod")
        val text = params.text()
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
        val applParams = paramsToExtract.map(_._1.decoded).mkString(", ")
        val newIndent = stat.startPos.startColumnPadding
        val oldIndentLen = head.startPos.startColumnPadding.length()
        val toExtract =
          textToExtract(
            text,
            head.startPos.start,
            appl.endPos.end,
            newIndent,
            oldIndentLen,
          )
        val defText =
          if extracted.length > 1 then
            s"def $name$typeParams($methodParams): $applType =\n${toExtract}\n\n$newIndent"
          else
            s"def $name$typeParams($methodParams): $applType =\n${toExtract}\n\n$newIndent"
        val replacedText = s"$name($applParams)"
        List(
          new l.TextEdit(
            range,
            replacedText,
          ),
          new l.TextEdit(
            l.Range(extractionPos, extractionPos),
            defText,
          ),
        )

    edits.getOrElse(Nil)
  end extractMethod
end ExtractMethodProvider
