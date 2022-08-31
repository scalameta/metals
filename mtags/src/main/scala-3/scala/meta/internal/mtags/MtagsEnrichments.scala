package scala.meta.internal.mtags

import java.net.URI

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.pc.MetalsInteractive
import scala.meta.internal.pc.SemanticdbSymbols
import scala.meta.pc.OffsetParams
import scala.meta.pc.ParentSymbols
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.Driver
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.SymDenotations.NoDenotation
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.Spans.Span
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
    def offsetToPos(offset: Int): l.Position =
      // dotty's `SourceFile.column` method treats tabs incorrectly.
      // If a line starts with tabs, they just don't count as symbols, resulting in a wrong editRange.
      // see: https://github.com/scalameta/metals/pull/3702
      val lineStartOffest = pos.source.startOfLine(offset)
      val line = pos.source.offsetToLine(lineStartOffest)
      val column = offset - lineStartOffest
      new l.Position(line, column)

    def toLSP: l.Range =
      new l.Range(
        offsetToPos(pos.start),
        offsetToPos(pos.end),
      )

    def withEnd(end: Int): SourcePosition =
      pos.withSpan(pos.span.withEnd(end))

    def withStart(end: Int): SourcePosition =
      pos.withSpan(pos.span.withStart(end))

    def focusAt(point: Int): SourcePosition =
      pos.withSpan(pos.span.withPoint(point).focus)

    def toLocation: Option[l.Location] =
      for
        uri <- InteractiveDriver.toUriOption(pos.source)
        range <- if pos.exists then Some(pos.toLSP) else None
      yield new l.Location(uri.toString, range)
  end extension

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

    def companion: Symbol =
      if sym.is(Module) then sym.companionClass else sym.companionModule

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

    // Returns true if this symbol is locally defined from an old version of the source file.
    def isStale: Boolean =
      sym.sourcePos.span.exists && {
        val source = ctx.source
        if source ne sym.source then
          !source.content.startsWith(
            sym.decodedName.toString(),
            sym.sourcePos.span.point,
          )
        else false
      }
  end extension

  extension (name: Name)(using Context)
    def decoded: String = name.stripModuleClassSuffix.show

  extension (s: String)
    def backticked: String =
      KeywordWrapper.Scala3.backtickWrap(s)

  extension (search: SymbolSearch)
    def symbolDocumentation(symbol: Symbol)(using
        Context
    ): Option[SymbolDocumentation] =
      def toSemanticdbSymbol(symbol: Symbol) =
        SemanticdbSymbols.symbolName(
          if !symbol.is(JavaDefined) && symbol.isPrimaryConstructor then
            symbol.owner
          else symbol
        )
      val sym = toSemanticdbSymbol(symbol)
      val documentation = search.documentation(
        sym,
        () => symbol.allOverriddenSymbols.map(toSemanticdbSymbol).toList.asJava,
      )
      if documentation.isPresent then Some(documentation.get())
      else None
    end symbolDocumentation
  end extension

  extension (tree: Tree)
    def qual: Tree =
      tree match
        case Apply(q, _) => q.qual
        case TypeApply(q, _) => q.qual
        case AppliedTypeTree(q, _) => q.qual
        case Select(q, _) => q
        case _ => tree

    def seenFrom(sym: Symbol)(using Context): (Type, Symbol) =
      try
        val pre = tree.qual
        val denot = sym.denot.asSeenFrom(pre.tpe.widenTermRefExpr)
        (denot.info, sym.withUpdatedTpe(denot.info))
      catch case NonFatal(e) => (sym.info, sym)
  end extension

  extension (imp: Import)
    def selector(span: Span)(using Context): Option[Symbol] =
      for sel <- imp.selectors.find(_.span.contains(span))
      yield imp.expr.symbol.info.member(sel.name).symbol

  extension (denot: Denotation)
    def allSymbols: List[Symbol] =
      denot match
        case MultiDenotation(denot1, denot2) =>
          List(
            denot1.allSymbols,
            denot2.allSymbols,
          ).flatten
        case NoDenotation => Nil
        case _ =>
          List(denot.symbol)
end MtagsEnrichments
