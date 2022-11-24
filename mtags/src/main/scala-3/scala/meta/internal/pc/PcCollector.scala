package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.meta as m

import scala.meta.Import.apply
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.ast.untpd
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

abstract class PcCollector[T](driver: InteractiveDriver, params: OffsetParams):
  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)
  val uri = params.uri()
  val filePath = Paths.get(uri)
  val sourceText = params.text

  def collect(tree: Tree, pos: SourcePosition): T

  def adjust(
      pos: SourcePosition,
      forHighlight: Boolean = false,
  ): (SourcePosition, Boolean) =
    val isBackticked =
      sourceText(pos.start) == '`' && sourceText(pos.end - 1) == '`'
    // when the old name contains backticks, the position is incorrect
    val isOldNameBackticked = sourceText(pos.start) != '`' &&
      sourceText(pos.start - 1) == '`' &&
      sourceText(pos.end) == '`'

    if isBackticked && !forHighlight then
      (pos.withStart(pos.start + 1).withEnd(pos.`end` - 1), true)
    else if isOldNameBackticked then
      (pos.withStart(pos.start - 1).withEnd(pos.`end` + 1), false)
    else (pos, false)
  end adjust

  def result(): List[T] =
    val source =
      SourceFile.virtual(filePath.toString, sourceText)
    driver.run(uri, source)
    given ctx: Context = driver.currentCtx
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
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

    def symbolAlternatives(sym: Symbol) =
      val all =
        if sym.is(Flags.ModuleClass) then
          Set(sym, sym.companionModule, sym.companionModule.companion)
        else if sym.isClass then
          Set(sym, sym.companionModule, sym.companion.moduleClass)
        else if sym.is(Flags.Module) then
          Set(sym, sym.companionClass, sym.moduleClass)
        else if sym.isTerm then
          val info =
            if sym.owner.isClass then sym.owner.info
            else sym.owner.owner.info
          Set(
            sym,
            info.member(sym.asTerm.name.setterName).symbol,
            info.member(sym.asTerm.name.getterName).symbol,
          ) ++ sym.allOverriddenSymbols.toSet
        else Set(sym)
      all.filter(s => s != NoSymbol && !s.isError)
    end symbolAlternatives

    // First identify the symbol we are at, comments identify @@ as current cursor position
    def soughtSymbols(path: List[Tree]): Option[Set[Symbol]] = path match
      /* simple identifier:
       * val a = val@@ue + value
       */
      case (id: Ident) :: _ =>
        Some(symbolAlternatives(id.symbol))
      /* simple selector:
       * object.val@@ue
       */
      case (sel: Select) :: _ if sel.nameSpan.contains(pos.span) =>
        Some(symbolAlternatives(sel.symbol))
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
              Set(
                s,
                s.owner.owner.companion.info.member(s.name).symbol,
                s.owner.owner.info.member(s.name).symbol,
              )
                .filter(_ != NoSymbol)
            else Set(s)
          }
        else None
        end if
      /* all definitions:
       * def fo@@o = ???
       * class Fo@@o = ???
       * etc.
       */
      case (df: NamedDefTree) :: _ if df.nameSpan.contains(pos.span) =>
        Some(symbolAlternatives(df.symbol))
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
        imp.selector(pos.span).map(symbolAlternatives)

      case _ =>
        None

    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    soughtSymbols(path) match
      case Some(sought) =>
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
            owners.exists(_.span.point == named.symbol.owner.span.point)

        def soughtOrOverride(sym: Symbol) =
          sought(sym) || sym.allOverriddenSymbols.exists(sought(_))

        def collectNames(
            occurences: Set[T],
            tree: Tree,
        ): Set[T] =
          tree match
            /**
             * All indentifiers such as:
             * val a = <<b>>
             */
            case ident: Ident
                if !ident.span.isZeroExtent &&
                  (soughtOrOverride(ident.symbol) ||
                    isForComprehensionOwner(ident)) =>
              ident.symbol.nameBackticked
              occurences + collect(
                ident,
                ident.sourcePos,
              )
            /**
             * All select statements such as:
             * val a = hello.<<b>>
             */
            case sel: Select
                if soughtOrOverride(sel.symbol) && !sel.span.isZeroExtent =>
              occurences + collect(
                sel,
                pos.withSpan(sel.nameSpan),
              )
            /* all definitions:
             * def <<foo>> = ???
             * class <<Foo>> = ???
             * etc.
             */
            case df: NamedDefTree
                if soughtOrOverride(df.symbol) &&
                  !df.span.isZeroExtent && !df.symbol.isSetter =>
              occurences + collect(
                df,
                pos.withSpan(df.nameSpan),
              )
            /* Named parameters don't have symbol so we need to check the owner
             * foo(<<name>> = "abc")
             * User(<<name>> = "abc")
             * etc.
             */
            case apply: Apply =>
              val namedParam = apply.args.collectFirst {
                case arg: NamedArg
                    if sought.exists(sym =>
                      sym.name == arg.name &&
                        // foo(name = "123") for normal params
                        (sym.owner == apply.symbol ||
                          // Bar(name = "123") for case class, copy and apply methods
                          apply.symbol.is(Flags.Synthetic) &&
                          (sym.owner == apply.symbol.owner.companion || sym.owner == apply.symbol.owner))
                    ) =>
                  arg
              }
              namedParam match
                case Some(arg) =>
                  val realName = arg.name.stripModuleClassSuffix.lastPart
                  occurences + collect(
                    arg,
                    pos
                      .withSpan(
                        arg.span.withEnd(arg.span.start + realName.length)
                      ),
                  )

                case None =>
                  occurences
              end match
            /**
             * For traversing annotations:
             * @<<JsonNotification>>("")
             * def params() = ???
             */
            case mdf: MemberDef if mdf.mods.annotations.nonEmpty =>
              val trees = collectTrees(mdf.mods.annotations)
              val traverser =
                new DeepFolder[Set[T]](collectNames)
              trees.foldLeft(occurences) { case (set, tree) =>
                traverser(set, tree)
              }
            /**
             * For traversing import selectors:
             * import scala.util.<<Try>>
             */
            case imp: Import if owners(imp.expr.symbol) =>
              imp.selectors
                .collect {
                  case sel if soughtNames(sel.name) =>
                    // Show both rename and main together
                    val spans =
                      if (!sel.renamed.isEmpty) then
                        Set(sel.renamed.span, sel.imported.span)
                      else Set(sel.imported.span)
                    spans.map { span =>
                      collect(
                        imp,
                        pos.withSpan(span),
                      )
                    }
                }
                .flatten
                .toSet ++ occurences
            case inl: Inlined =>
              val traverser =
                new DeepFolder[Set[T]](collectNames)
              val trees = inl.call :: inl.expansion :: inl.bindings
              trees.foldLeft(occurences) { case (set, tree) =>
                traverser(set, tree)
              }
            case o =>
              occurences

        val traverser = new DeepFolder[Set[T]](collectNames)
        val all = traverser(Set.empty[T], unit.tpdTree)

        all.toList
      case None => Nil
    end match

  end result

  // @note (tgodzik) Not sure currently how to get rid of the warning, but looks to correctly
  @nowarn
  private def collectTrees(trees: Iterable[Positioned]): Iterable[Tree] =
    trees.collect { case t: Tree =>
      t
    }
end PcCollector
