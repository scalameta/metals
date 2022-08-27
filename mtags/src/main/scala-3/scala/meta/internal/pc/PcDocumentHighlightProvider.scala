package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.meta as m

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
import dotty.tools.dotc.util.Spans.Span
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind

object PcDocumentHighlightProvider:
  private val caseClassSynthetics: Set[Name] = Set(nme.apply, nme.copy)

  def higlights(
      driver: InteractiveDriver,
      params: OffsetParams,
  ): List[DocumentHighlight] =
    val uri = params.uri()
    val filePath = Paths.get(uri)
    val sourceText = params.text
    val source =
      SourceFile.virtual(filePath.toString, sourceText)
    driver.run(uri, source)
    val unit = driver.currentCtx.run.units.head
    val pos = driver.sourcePosition(params)
    val path =
      Interactive.pathTo(driver.openedTrees(uri), pos)(using driver.currentCtx)

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
          )
        else Set(sym)
      all.filter(_ != NoSymbol)
    end symbolAlternatives
    given ctx: Context = driver.currentCtx

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
      case (df: MemberDef) :: _ if df.span.contains(pos.span) =>
        val annotTree = df.mods.annotations.find { t =>
          t.span.contains(pos.span)
        }
        collectTrees(annotTree).flatMap { t =>
          soughtSymbols(
            Interactive.pathTo(t, pos.span)
          )
        }.headOption

      case _ =>
        None

    // Now find all matching symbols in the document, comments identify <<>> as the symbol we are looking for
    soughtSymbols(path) match
      case Some(sought) =>
        def collectNames(
            highlights: Set[DocumentHighlight],
            tree: Tree,
        ): Set[DocumentHighlight] =
          tree match
            /**
             * All indentifiers such as:
             * val a = <<b>>
             */
            case ident: Ident
                if sought(ident.symbol) && !ident.span.isZeroExtent =>
              highlights + new DocumentHighlight(
                ident.sourcePos.toLSP,
                DocumentHighlightKind.Read,
              )
            /**
             * All select statements such as:
             * val a = hello.<<b>>
             */
            case sel: Select if sought(sel.symbol) && !sel.span.isZeroExtent =>
              highlights + new DocumentHighlight(
                pos.withSpan(sel.nameSpan).toLSP,
                DocumentHighlightKind.Read,
              )
            /* all definitions:
             * def <<foo>> = ???
             * class <<Foo>> = ???
             * etc.
             */
            case df: NamedDefTree
                if sought(
                  df.symbol
                ) && !df.span.isZeroExtent && !df.symbol.isSetter =>
              highlights + new DocumentHighlight(
                pos.withSpan(df.nameSpan).toLSP,
                DocumentHighlightKind.Write,
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
                  highlights + new DocumentHighlight(
                    pos
                      .withSpan(
                        arg.span.withEnd(arg.span.start + realName.length)
                      )
                      .toLSP,
                    DocumentHighlightKind.Write,
                  )

                case None =>
                  highlights
            case mdf: MemberDef if mdf.mods.annotations.nonEmpty =>
              val trees = collectTrees(mdf.mods.annotations)
              val traverser =
                new DeepFolder[Set[DocumentHighlight]](collectNames)
              trees.foldLeft(highlights) { case (set, tree) =>
                traverser(set, tree)
              }
            case o =>
              highlights

        val traverser = new DeepFolder[Set[DocumentHighlight]](collectNames)
        val all = traverser(Set.empty[DocumentHighlight], unit.tpdTree)
        all.toList.distinct
      case None => Nil
    end match
  end higlights

  // @note (tgodzik) Not sure currently how to get rid of the warning, but looks to correctly
  @nowarn
  private def collectTrees(trees: Iterable[Positioned]): Iterable[Tree] =
    trees.collect { case t: Tree =>
      t
    }
end PcDocumentHighlightProvider
