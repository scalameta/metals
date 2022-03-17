package scala.meta.internal.mtags

import java.net.URI

import scala.annotation.tailrec

import scala.meta.internal.pc.MetalsInteractive
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import dotty.tools.dotc.Driver
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.{lsp4j as l}

object MtagsEnrichments extends CommonMtagsEnrichments:

  extension (driver: InteractiveDriver)

    def sourcePosition(
        params: OffsetParams
    ): SourcePosition =
      val uri = params.uri
      val source = driver.openedFiles(uri)
      val span = params match
        case p: RangeParams if p.offset != p.endOffset =>
          p.trimWhitespaceInRange.fold {
            Spans.Span(p.offset, p.endOffset)
          } {
            case trimmed: RangeParams =>
              Spans.Span(trimmed.offset, trimmed.endOffset)
            case offset =>
              Spans.Span(p.offset, p.offset)
          }
        case _ => Spans.Span(params.offset)

      new SourcePosition(source, span)
    end sourcePosition

    def localContext(params: OffsetParams): Context =
      if driver.currentCtx.run.units.isEmpty then
        throw new RuntimeException(
          "No source files were passed to the Scala 3 presentation compiler"
        )
      val unit = driver.currentCtx.run.units.head
      val tree = unit.tpdTree
      val pos = driver.sourcePosition(params)
      val path =
        Interactive.pathTo(driver.openedTrees(params.uri), pos)(using
          driver.currentCtx
        )

      val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
      val tpdPath =
        Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
          newctx
        )
      MetalsInteractive.contextOfPath(tpdPath)(using newctx)
    end localContext

  end extension

  extension (pos: SourcePosition)
    def toLSP: l.Range =
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )

    def toLocation: Option[l.Location] =
      for
        uri <- InteractiveDriver.toUriOption(pos.source)
        range <- if pos.exists then Some(pos.toLSP) else None
      yield new l.Location(uri.toString, range)

  extension (sym: Symbol)(using Context)
    def fullNameBackticked: String =
      @tailrec
      def loop(acc: List[String], sym: Symbol): List[String] =
        if sym == NoSymbol || sym.isRoot || sym.isEmptyPackage then acc
        else if sym.isPackageObject then loop(acc, sym.owner)
        else
          val v = KeywordWrapper.Scala3.backtickWrap(sym.decodedName)
          loop(v :: acc, sym.owner)
      loop(Nil, sym).mkString(".")

    def decodedName: String = sym.name.decoded

    def nameBackticked: String =
      KeywordWrapper.Scala3.backtickWrap(sym.decodedName)

    def withUpdatedTpe(tpe: Type): Symbol =
      val upd = sym.copy(info = tpe)
      val paramsWithFlags =
        sym.paramSymss
          .zip(upd.paramSymss)
          .map((l1, l2) =>
            l1.zip(l2)
              .map((s1, s2) =>
                s2.flags = s1.flags
                s2
              )
          )
      upd.rawParamss = paramsWithFlags
      upd
    end withUpdatedTpe
  end extension

  extension (name: Name)(using Context)
    def decoded: String = name.stripModuleClassSuffix.show

  extension (s: String)
    def backticked: String =
      KeywordWrapper.Scala3.backtickWrap(s)

  extension (params: OffsetParams)
    def isWhitespace: Boolean =
      params.offset() < 0 ||
        params.offset() >= params.text().length ||
        params.text().charAt(params.offset()).isWhitespace

end MtagsEnrichments
