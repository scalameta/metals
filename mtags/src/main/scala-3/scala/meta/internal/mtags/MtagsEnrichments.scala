package scala.meta.internal.mtags

import scala.annotation.tailrec
import scala.util.control.NonFatal

import scala.meta.internal.jdk.CollectionConverters.*
import scala.meta.internal.pc.SemanticdbSymbols
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams
import scala.meta.pc.SymbolDocumentation
import scala.meta.pc.SymbolSearch

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.SymDenotations.NoDenotation
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.AppliedType
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.lsp4j as l

object MtagsEnrichments extends ScalametaCommonEnrichments:

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

    def latestRun = 
      if driver.currentCtx.run.units.nonEmpty then
        driver.currentCtx.run.units.head
      else
        throw new RuntimeException(
          "No source files were compiled. Might be an error in the compiler itself."
        )
    def localContext(params: OffsetParams): Context =
      val unit = driver.latestRun
      val pos = driver.sourcePosition(params)
      val newctx = driver.currentCtx.fresh.setCompilationUnit(unit)
      val tpdPath =
        Interactive.pathTo(newctx.compilationUnit.tpdTree, pos.span)(using
          newctx
        )
      Interactive.contextOfPath(tpdPath)(using newctx)
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

    def toLsp: l.Range =
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
        range <- if pos.exists then Some(pos.toLsp) else None
      yield new l.Location(uri.toString, range)

    def encloses(other: SourcePosition): Boolean =
      pos.start <= other.start && pos.end >= other.end

    def encloses(other: RangeParams): Boolean =
      pos.start <= other.offset() && pos.end >= other.endOffset()

    /**
     * @return (adjusted position, should strip backticks)
     */
    def adjust(
        text: Array[Char],
        forRename: Boolean = false,
    )(using Context): (SourcePosition, Boolean) =
      if !pos.span.isCorrect(text) then (pos, false)
      else
        val pos0 =
          val span = pos.span
          if span.exists && span.point > span.end then
            pos.withSpan(
              span
                .withStart(span.point)
                .withEnd(span.point + (span.end - span.start))
            )
          else pos

        val pos1 =
          if pos0.end > 0 && text(pos0.end - 1) == ',' then
            pos0.withEnd(pos0.end - 1)
          else pos0
        val isBackticked =
          text(pos1.start) == '`' &&
            pos1.end > 0 &&
            text(pos1.end - 1) == '`'
        // when the old name contains backticks, the position is incorrect
        val isOldNameBackticked = text(pos1.start) != '`' &&
          pos1.start > 0 &&
          text(pos1.start - 1) == '`' &&
          text(pos1.end) == '`'
        if isBackticked && forRename then
          (pos1.withStart(pos1.start + 1).withEnd(pos1.end - 1), true)
        else if isOldNameBackticked then
          (pos1.withStart(pos1.start - 1).withEnd(pos1.end + 1), false)
        else (pos1, false)
    end adjust
  end extension

  extension (span: Span)
    def isCorrect(text: Array[Char]): Boolean =
      !span.isZeroExtent && span.exists && span.start < text.size && span.end <= text.size

  extension (pos: RangeParams)
    def encloses(other: SourcePosition): Boolean =
      pos.offset() <= other.start && pos.endOffset() >= other.end

  extension (sym: Symbol)(using Context)
    def fullNameBackticked: String = fullNameBackticked(Set.empty[String])

    def fullNameBackticked(backtickSoftKeyword: Boolean = true): String =
      if backtickSoftKeyword then fullNameBackticked(Set.empty[String])
      else fullNameBackticked(KeywordWrapper.Scala3SoftKeywords)

    def fullNameBackticked(exclusions: Set[String]): String =
      @tailrec
      def loop(acc: List[String], sym: Symbol): List[String] =
        if sym == NoSymbol || sym.isRoot || sym.isEmptyPackage then acc
        else if sym.isPackageObject then loop(acc, sym.owner)
        else
          val v = this.nameBackticked(sym)(exclusions)
          loop(v :: acc, sym.owner)
      loop(Nil, sym).mkString(".")

    def decodedName: String = sym.name.decoded

    def companion: Symbol =
      if sym.is(Module) then sym.companionClass else sym.companionModule

    def nameBackticked: String = nameBackticked(Set.empty[String])

    def nameBackticked(backtickSoftKeyword: Boolean = true): String =
      if backtickSoftKeyword then nameBackticked(Set.empty[String])
      else nameBackticked(KeywordWrapper.Scala3SoftKeywords)

    def nameBackticked(exclusions: Set[String]): String =
      KeywordWrapper.Scala3.backtickWrap(sym.decodedName, exclusions)

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
    def backticked: String = s.backticked()

    def backticked(backtickSoftKeyword: Boolean = true): String =
      if backtickSoftKeyword then KeywordWrapper.Scala3.backtickWrap(s)
      else
        KeywordWrapper.Scala3.backtickWrap(s, KeywordWrapper.Scala3SoftKeywords)

    def stripBackticks: String = s.stripPrefix("`").stripSuffix("`")

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
      def parentSymbols =
        if symbol.name == nme.apply && symbol.maybeOwner.is(ModuleClass) then
          List(
            symbol.maybeOwner,
            symbol.maybeOwner.companion,
          ).filter(_ != NoSymbol) ++ symbol.allOverriddenSymbols
        else symbol.allOverriddenSymbols

      val documentation = search.documentation(
        sym,
        () => parentSymbols.map(toSemanticdbSymbol).toList.asJava,
      )
      if documentation.isPresent then Some(documentation.get())
      else None
    end symbolDocumentation
  end extension

  private val infixNames =
    Set(nme.apply, nme.unapply, nme.unapplySeq)
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
        val denot = sym.denot.asSeenFrom(pre.typeOpt.widenTermRefExpr)
        (denot.info, sym.withUpdatedTpe(denot.info))
      catch case NonFatal(e) => (sym.info, sym)

    def isInfix(using ctx: Context) =
      tree match
        case Select(New(_), _) => false
        case Select(_, name: TermName) if infixNames(name) => false
        case Select(This(_), _) => false
        // is a select statement without a dot `qual.name`
        case sel @ Select(qual, _) if !sel.symbol.is(Flags.Synthetic) =>
          val source = tree.source.content()
          qual.span.end >= sel.nameSpan.start ||
          !source.slice(qual.span.end, sel.nameSpan.start).contains('.')
        case _ => false

    def children(using Context): List[Tree] =
      val collector = new TreeAccumulator[List[Tree]]:
        def apply(x: List[Tree], tree: Tree)(using Context): List[Tree] =
          tree :: x
      collector
        .foldOver(Nil, tree)
        .reverse

    /**
     * Returns the children of the tree that overlap with the given span.
     */
    def enclosedChildren(span: Span)(using Context): List[Tree] =
      tree.children
        .filter(tree =>
          tree.sourcePos.exists && tree.span.start <= span.end && tree.span.end >= span.start
        )
    end enclosedChildren
  end extension

  extension (imp: Import)
    def selector(span: Span)(using Context): Option[Symbol] =
      for sel <- imp.selectors.find(_.span.contains(span))
      yield imp.expr.symbol.info.member(sel.name).symbol

  private val forCompMethods =
    Set(nme.map, nme.flatMap, nme.withFilter, nme.foreach)
  extension (sel: Select)
    def isForComprehensionMethod(using Context): Boolean =
      val syntheticName = sel.name match
        case name: TermName => forCompMethods(name)
        case _ => false
      val wrongSpan = sel.qualifier.span.contains(sel.nameSpan)
      syntheticName && wrongSpan
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

  extension (path: List[Tree])
    def expandRangeToEnclosingApply(
        pos: SourcePosition
    )(using Context): List[Tree] =
      def tryTail(enclosing: List[Tree]): Option[List[Tree]] =
        enclosing match
          case Nil => None
          case head :: tail =>
            head match
              case t: GenericApply
                  if t.fun.srcPos.span.contains(
                    pos.span
                  ) && !t.typeOpt.isErroneous =>
                tryTail(tail).orElse(Some(enclosing))
              case in: Inlined =>
                tryTail(tail).orElse(Some(enclosing))
              case New(_) =>
                tail match
                  case Nil => None
                  case Select(_, _) :: next =>
                    tryTail(next)
                  case _ =>
                    None
              case sel @ Select(qual, nme.apply) if qual.span == sel.nameSpan =>
                tryTail(tail).orElse(Some(enclosing))
              case _ =>
                None
      path match
        case head :: tail =>
          tryTail(tail).getOrElse(path)
        case _ =>
          List(EmptyTree)
    end expandRangeToEnclosingApply
  end extension

  extension (tpe: Type)
    def metalsDealias(using Context): Type =
      tpe.dealias match
        case app @ AppliedType(tycon, params) =>
          // we dealias applied type params by hand, because `dealias` doesn't do it
          AppliedType(tycon, params.map(_.metalsDealias))
        case dealised => dealised

end MtagsEnrichments
