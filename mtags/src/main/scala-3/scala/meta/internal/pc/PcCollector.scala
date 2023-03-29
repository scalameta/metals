package scala.meta.internal.pc

import java.nio.file.Paths

import scala.meta as m

import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams
import scala.meta.pc.VirtualFileParams

import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.ExtMethods
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans.Span

abstract class PcCollector[T](
    driver: InteractiveDriver,
    params: VirtualFileParams,
):
  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)
  val uri = params.uri()
  val filePath = Paths.get(uri)
  val sourceText = params.text
  val source =
    SourceFile.virtual(filePath.toString, sourceText)
  driver.run(uri, source)
  given ctx: Context = driver.currentCtx

  val unit = driver.currentCtx.run.units.head
  val offset = params match
    case op: OffsetParams => op.offset()
    case _ => 0
  val offsetParams =
    params match
      case op: OffsetParams => op
      case _ =>
        CompilerOffsetParams(params.uri(), params.text(), 0, params.token())
  val pos = driver.sourcePosition(offsetParams)
  val rawPath =
    Interactive
      .pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)
      .dropWhile(t => // NamedArg anyway doesn't have symbol
        t.symbol == NoSymbol && !t.isInstanceOf[NamedArg] ||
          // same issue https://github.com/lampepfl/dotty/issues/15937 as below
          t.isInstanceOf[TypeTree]
      )

  val path = rawPath match
    // For type it will sometimes go into the wrong tree since TypeTree also contains the same span
    // https://github.com/lampepfl/dotty/issues/15937
    case TypeApply(sel: Select, _) :: tail if sel.span.contains(pos.span) =>
      Interactive.pathTo(sel, pos.span) ::: rawPath
    case _ => rawPath
  def collect(
      parent: Option[Tree]
  )(tree: Tree, pos: SourcePosition, symbol: Option[Symbol]): T

  /**
   * @return (adjusted position, should strip backticks)
   */
  def adjust(
      pos1: SourcePosition,
      forRename: Boolean = false,
  ): (SourcePosition, Boolean) =
    if !pos1.span.isCorrect then (pos1, false)
    else
      val pos0 =
        val span = pos1.span
        if span.exists && span.point > span.end then
          pos1.withSpan(
            span
              .withStart(span.point)
              .withEnd(span.point + (span.end - span.start))
          )
        else pos1

      val pos =
        if sourceText(pos0.`end` - 1) == ',' then pos0.withEnd(pos0.`end` - 1)
        else pos0
      val isBackticked =
        sourceText(pos.start) == '`' && sourceText(pos.end - 1) == '`'
      // when the old name contains backticks, the position is incorrect
      val isOldNameBackticked = sourceText(pos.start) != '`' &&
        sourceText(pos.start - 1) == '`' &&
        sourceText(pos.end) == '`'
      if isBackticked && forRename then
        (pos.withStart(pos.start + 1).withEnd(pos.`end` - 1), true)
      else if isOldNameBackticked then
        (pos.withStart(pos.start - 1).withEnd(pos.`end` + 1), false)
      else (pos, false)
  end adjust

  def symbolAlternatives(sym: Symbol) =
    val all =
      if sym.is(Flags.ModuleClass) then
        Set(sym, sym.companionModule, sym.companionModule.companion)
      else if sym.isClass then
        Set(sym, sym.companionModule, sym.companion.moduleClass)
      else if sym.is(Flags.Module) then
        Set(sym, sym.companionClass, sym.moduleClass)
      else if sym.isTerm && (sym.owner.isClass || sym.owner.isConstructor)
      then
        val info =
          if sym.owner.isClass then sym.owner.info else sym.owner.owner.info
        Set(
          sym,
          info.member(sym.asTerm.name.setterName).symbol,
          info.member(sym.asTerm.name.getterName).symbol,
        ) ++ sym.allOverriddenSymbols.toSet
      else Set(sym)
    all.filter(s => s != NoSymbol && !s.isError)
  end symbolAlternatives

  private def isGeneratedGiven(df: NamedDefTree)(using Context) =
    val nameSpan = df.nameSpan
    df.symbol.is(Flags.Given) && sourceText.substring(
      nameSpan.start,
      nameSpan.end,
    ) != df.name.toString()

  // First identify the symbol we are at, comments identify @@ as current cursor position
  def soughtSymbols(path: List[Tree]): Option[(Set[Symbol], SourcePosition)] =
    val sought = path match
      /* simple identifier:
       * val a = val@@ue + value
       */
      case (id: Ident) :: _ =>
        Some(symbolAlternatives(id.symbol), id.sourcePos)
      /* simple selector:
       * object.val@@ue
       */
      case (sel: Select) :: _ if selectNameSpan(sel).contains(pos.span) =>
        Some(symbolAlternatives(sel.symbol), pos.withSpan(sel.nameSpan))
      /* named argument:
       * foo(nam@@e = "123")
       */
      case (arg: NamedArg) :: (appl: Apply) :: _ =>
        val realName = arg.name.stripModuleClassSuffix.lastPart
        if pos.span.start > arg.span.start && pos.span.end < arg.span.point + realName.length
        then
          appl.symbol.paramSymss.flatten.find(_.name == arg.name).map { s =>
            // if it's a case class we need to look for parameters also
            if caseClassSynthetics(s.owner.name) && s.owner.is(Flags.Synthetic)
            then
              (
                Set(
                  s,
                  s.owner.owner.companion.info.member(s.name).symbol,
                  s.owner.owner.info.member(s.name).symbol,
                )
                  .filter(_ != NoSymbol),
                arg.sourcePos,
              )
            else (Set(s), arg.sourcePos)
          }
        else None
        end if
      /* all definitions:
       * def fo@@o = ???
       * class Fo@@o = ???
       * etc.
       */
      case (df: NamedDefTree) :: _
          if df.nameSpan.contains(pos.span) && !isGeneratedGiven(df) =>
        Some(symbolAlternatives(df.symbol), pos.withSpan(df.nameSpan))
      /**
       * For traversing annotations:
       * @JsonNo@@tification("")
       * def params() = ???
       */
      case (df: MemberDef) :: _ if df.span.contains(pos.span) =>
        val annotTree = df.mods.annotations.find { t =>
          t.span.contains(pos.span)
        }
        collectTrees(annotTree).flatMap { t =>
          soughtSymbols(
            Interactive.pathTo(t, pos.span)
          )
        }.headOption

      /* Import selectors:
       * import scala.util.Tr@@y
       */
      case (imp: Import) :: _ if imp.span.contains(pos.span) =>
        imp
          .selector(pos.span)
          .map(sym => (symbolAlternatives(sym), sym.sourcePos))

      case _ =>
        None

    sought match
      case Some(value) => sought
      case None =>
        // Fallback check for extension method parameter
        def collectExtMethods(
            acc: Set[(Symbol, SourcePosition)],
            tree: untpd.Tree,
        ) =
          tree match
            case ExtMethods(paramss, _) =>
              acc ++ paramss
                .flatMap(
                  _.map(p =>
                    (
                      p.symbol,
                      p.namePos,
                    )
                  )
                )
                .toSet
            case _ => acc
        val traverser =
          new untpd.UntypedDeepFolder[Set[(Symbol, SourcePosition)]](
            collectExtMethods
          )
        val extParams: Set[(Symbol, SourcePosition)] =
          traverser(Set.empty[(Symbol, SourcePosition)], unit.untpdTree)
        extParams.collectFirst {
          case (sym, symPos)
              if symPos.span.contains(
                pos.span
              ) && sym != NoSymbol && !sym.isError =>
            (symbolAlternatives(sym), symPos)
        }
    end match
  end soughtSymbols
  def result(): List[T] =
    params match
      case _: OffsetParams => resultWithSought()
      case _ => resultAllOccurences().toList

  def resultAllOccurences(): Set[T] =
    def noTreeFilter = (tree: Tree) => true
    def noSoughtFilter = (f: Set[Symbol] => Boolean) => true

    traverseSought(noTreeFilter, noSoughtFilter)

  def resultWithSought(): List[T] =
    soughtSymbols(path) match
      case Some((sought, _)) =>
        lazy val owners = sought
          .flatMap { s => Set(s.owner, s.owner.companionModule) }
          .filter(_ != NoSymbol)
        lazy val soughtNames: Set[Name] = sought.map(_.name)

        /*
         * For comprehensions have two owners, one for the enumerators and one for
         * yield. This is a heuristic to find that out.
         */
        def isForComprehensionOwner(named: NameTree) =
          soughtNames(named.name) &&
            scala.util
              .Try(named.symbol.owner)
              .toOption
              .exists(_.isAnonymousFunction) &&
            owners.exists(o =>
              o.span.exists && o.span.point == named.symbol.owner.span.point
            )

        def soughtOrOverride(sym: Symbol) =
          sought(sym) || sym.allOverriddenSymbols.exists(sought(_))

        def isExtensionParam(sym: Symbol) =
          val extensionParam = for
            extensionMethod <- sym.ownersIterator.drop(1).nextOption()
            if extensionMethod.is(Flags.ExtensionMethod)
          yield extensionMethod.extensionParam
          extensionParam.exists(param => sym == param)

        lazy val extensionParam = sought.exists(isExtensionParam)

        def soughtTreeFilter(tree: Tree): Boolean =
          tree match
            case ident: Ident
                if (soughtOrOverride(ident.symbol) ||
                  isForComprehensionOwner(ident) ||
                  (extensionParam && isExtensionParam(ident.symbol))) =>
              true
            case sel: Select if soughtOrOverride(sel.symbol) => true
            case df: NamedDefTree
                if soughtOrOverride(df.symbol) && !df.symbol.isSetter =>
              true
            case imp: Import if owners(imp.expr.symbol) => true
            case _ => false

        def soughtFilter(f: Set[Symbol] => Boolean): Boolean =
          f(sought)

        traverseSought(soughtTreeFilter, soughtFilter).toList

      case None => Nil

  extension (span: Span)
    def isCorrect =
      !span.isZeroExtent && span.exists && span.start < sourceText.size && span.end <= sourceText.size

  def traverseSought(
      filter: Tree => Boolean,
      soughtFilter: (Set[Symbol] => Boolean) => Boolean,
  ): Set[T] =
    def collectNamesWithParent(
        occurences: Set[T],
        tree: Tree,
        parent: Option[Tree],
    ): Set[T] =
      def collect(
          tree: Tree,
          pos: SourcePosition,
          symbol: Option[Symbol] = None,
      ) =
        this.collect(parent)(tree, pos, symbol)

      tree match
        /**
         * All indentifiers such as:
         * val a = <<b>>
         */
        case ident: Ident if ident.span.isCorrect && filter(ident) =>
          // symbols will differ for params in different ext methods, but source pos will be the same
          if soughtFilter(sought =>
              sought.exists(_.sourcePos == ident.symbol.sourcePos)
            )
          then
            occurences + collect(
              ident,
              ident.sourcePos,
            )
          else occurences
        /**
         * All select statements such as:
         * val a = hello.<<b>>
         */
        case sel: Select if sel.span.isCorrect && filter(sel) =>
          occurences + collect(
            sel,
            pos.withSpan(selectNameSpan(sel)),
          )
        /* all definitions:
         * def <<foo>> = ???
         * class <<Foo>> = ???
         * etc.
         */
        case df: NamedDefTree
            if df.span.isCorrect && df.nameSpan.isCorrect &&
              filter(df) && !isGeneratedGiven(df) =>
          val annots = collectTrees(df.mods.annotations)
          val traverser =
            new PcCollector.DeepFolderWithParent[Set[T]](
              collectNamesWithParent
            )
          annots.foldLeft(
            occurences + collect(
              df,
              pos.withSpan(df.nameSpan),
            )
          ) { case (set, tree) =>
            traverser(set, tree)
          }

        /* Named parameters don't have symbol so we need to check the owner
         * foo(<<name>> = "abc")
         * User(<<name>> = "abc")
         * etc.
         */
        case apply: Apply =>
          val args: List[NamedArg] = apply.args.collect {
            case arg: NamedArg
                if soughtFilter(sought =>
                  sought.exists(sym =>
                    sym.name == arg.name &&
                      // foo(name = "123") for normal params
                      (sym.owner == apply.symbol ||
                        // Bar(name = "123") for case class, copy and apply methods
                        apply.symbol.is(Flags.Synthetic) &&
                        (sym.owner == apply.symbol.owner.companion || sym.owner == apply.symbol.owner))
                  )
                ) =>
              arg
          }
          val named = args.map { arg =>
            val realName = arg.name.stripModuleClassSuffix.lastPart
            val sym = apply.symbol.paramSymss.flatten
              .find(_.name == realName)
            collect(
              arg,
              pos
                .withSpan(
                  arg.span
                    .withEnd(arg.span.start + realName.length)
                    .withPoint(arg.span.start)
                ),
              sym,
            )
          }
          occurences ++ named

        /**
         * For traversing annotations:
         * @<<JsonNotification>>("")
         * def params() = ???
         */
        case mdf: MemberDef if mdf.mods.annotations.nonEmpty =>
          val trees = collectTrees(mdf.mods.annotations)
          val traverser =
            new PcCollector.DeepFolderWithParent[Set[T]](
              collectNamesWithParent
            )
          trees.foldLeft(occurences) { case (set, tree) =>
            traverser(set, tree)
          }
        /**
         * For traversing import selectors:
         * import scala.util.<<Try>>
         */
        case imp: Import if filter(imp) =>
          imp.selectors
            .collect {
              case sel: ImportSelector
                  if soughtFilter(sought =>
                    sought.exists(_.name == sel.name)
                  ) =>
                // Show both rename and main together
                val spans =
                  if (!sel.renamed.isEmpty) then
                    Set(sel.renamed.span, sel.imported.span)
                  else Set(sel.imported.span)
                spans.filter(_.isCorrect).map { span =>
                  collect(
                    imp,
                    pos.withSpan(span),
                    Some(imp.expr.symbol.info.member(sel.name).symbol),
                  )
                }
            }
            .flatten
            .toSet ++ occurences
        case inl: Inlined =>
          val traverser =
            new PcCollector.DeepFolderWithParent[Set[T]](
              collectNamesWithParent
            )
          val trees = inl.call :: inl.bindings
          trees.foldLeft(occurences) { case (set, tree) =>
            traverser(set, tree)
          }
        case o =>
          occurences
      end match
    end collectNamesWithParent

    val traverser =
      new PcCollector.DeepFolderWithParent[Set[T]](collectNamesWithParent)
    val all = traverser(Set.empty[T], unit.tpdTree)
    all
  end traverseSought

  // @note (tgodzik) Not sure currently how to get rid of the warning, but looks to correctly
  // @nowarn
  private def collectTrees(trees: Iterable[Positioned]): Iterable[Tree] =
    trees.collect { case t: Tree =>
      t
    }

  // NOTE: Connected to https://github.com/lampepfl/dotty/issues/16771
  // `sel.nameSpan` is calculated incorrectly in (1 + 2).toString
  // See test DocumentHighlightSuite.select-parentheses
  private def selectNameSpan(sel: Select): Span =
    val span = sel.span
    if span.exists then
      val point = span.point
      if sel.name.toTermName == nme.ERROR then Span(point)
      else if sel.qualifier.span.start > span.point then // right associative
        val realName = sel.name.stripModuleClassSuffix.lastPart
        Span(span.start, span.start + realName.length, point)
      else Span(point, span.end, point)
    else span
end PcCollector

object PcCollector:
  private class WithParentTraverser[X](f: (X, Tree, Option[Tree]) => X)
      extends TreeAccumulator[List[Tree]]:
    def apply(x: List[Tree], tree: Tree)(using Context): List[Tree] = tree :: x
    def traverse(acc: X, tree: Tree, parent: Option[Tree])(using Context): X =
      val res = f(acc, tree, parent)
      val children = foldOver(Nil, tree).reverse
      children.foldLeft(res)((a, c) => traverse(a, c, Some(tree)))

  // Folds over the tree as `DeepFolder` but `f` takes also the parent.
  class DeepFolderWithParent[X](f: (X, Tree, Option[Tree]) => X):
    private val traverser = WithParentTraverser[X](f)
    def apply(x: X, tree: Tree)(using Context) =
      traverser.traverse(x, tree, None)
end PcCollector
