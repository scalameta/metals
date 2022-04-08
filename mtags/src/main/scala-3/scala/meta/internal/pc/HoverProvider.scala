package scala.meta.internal.pc

import java.{util as ju}

import scala.util.control.NonFatal

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.printer.MetalsPrinter
import scala.meta.pc.OffsetParams

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.NameKinds.*
import dotty.tools.dotc.core.NameOps.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.SymDenotations.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.core.Types.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.util.ParsedComment
import dotty.tools.dotc.util.SourcePosition
import org.eclipse.lsp4j.Hover

object HoverProvider:

  def hover(
      params: OffsetParams,
      driver: InteractiveDriver
  ): ju.Optional[Hover] =
    val uri = params.uri
    val sourceFile = CompilerInterfaces.toSource(params.uri, params.text)
    driver.run(uri, sourceFile)

    given ctx: Context = driver.currentCtx
    val pos = driver.sourcePosition(params)
    val trees = driver.openedTrees(uri)
    val source = driver.openedFiles.get(uri)

    def typeFromPath(path: List[Tree]) =
      if path.isEmpty then NoType else path.head.tpe

    val path = Interactive.pathTo(trees, pos)
    val tp = typeFromPath(path)
    val tpw = tp.widenTermRefExpr
    // For expression we need to find all enclosing applies to get the exact generic type
    val enclosing = expandRangeToEnclosingApply(path, pos)
    val exprTp = typeFromPath(enclosing)
    val exprTpw = exprTp.widenTermRefExpr

    /**
     * Check if the given `sourcePos` is on the name of enclosing tree.
     * ```
     * // For example, if the postion is on `foo`, returns true
     * def foo(x: Int) = { ... }
     *      ^
     *
     * // On the other hand, it points to non-name position, return false.
     * def foo(x: Int) = { ... }
     *  ^
     * ```
     * @param path - path to the position given by `Interactive.pathTo`
     */
    def isHoveringOnName(path: List[Tree], sourcePos: SourcePosition): Boolean =
      def contains(tree: Tree): Boolean = tree match
        // e.g. interpolator-apply num"... $foo" has tree like
        // Select(Select(Apply(Ident(Xtension), List(...)), num), apply)
        case select: Select =>
          source.fold(false) { s =>
            SourceTree(select, s).namePos.contains(sourcePos)
          }
          || contains(select.qualifier)
        case tree: NameTree =>
          source.fold(false) { s =>
            SourceTree(tree, s).namePos.contains(sourcePos)
          }
        // TODO: check the positions for NamedArg and Import
        case _: NamedArg => true
        case _: Import => true
        case app: (Apply | TypeApply) => contains(app.fun)
        case _ => false
      end contains

      val enclosing = path
        .dropWhile(t => !t.symbol.exists && !t.isInstanceOf[NamedArg])
        .headOption
        .getOrElse(EmptyTree)
      contains(enclosing)
    end isHoveringOnName

    if tp.isError || tpw == NoType || tpw.isError || path.isEmpty ||
      (pos.isPoint // don't check isHoveringOnName for RangeHover
        && !isHoveringOnName(enclosing, pos))
    then ju.Optional.empty()
    else
      Interactive.enclosingSourceSymbols(enclosing, pos) match
        case Nil =>
          ju.Optional.empty()
        case symbols @ (symbol :: _) =>
          val docComments =
            symbols.flatMap(ParsedComment.docOf(_))
          val printerContext =
            driver.compilationUnits.get(uri) match
              case Some(unit) =>
                val newctx =
                  ctx.fresh.setCompilationUnit(unit)
                MetalsInteractive.contextOfPath(enclosing)(using newctx)
              case None => ctx
          val printer = MetalsPrinter.standard(IndexedContext(printerContext))

          val hoverString =
            tpw match
              // https://github.com/lampepfl/dotty/issues/8891
              case tpw: ImportType =>
                printer.hoverSymbol(symbol, symbol.paramRef)
              case _ =>
                val (tpe, sym) =
                  if symbol.isType then (symbol.typeRef, symbol)
                  else seenFrom(enclosing.head, symbol)

                val finalTpe =
                  if tpe != NoType then tpe
                  else tpw

                printer.hoverSymbol(sym, finalTpe)
            end match
          end hoverString

          val docString =
            docComments.map(_.renderAsMarkdown).mkString("\n")
          printer.expressionType(exprTpw) match
            case Some(expressionType) =>
              val forceExpressionType =
                !pos.span.isZeroExtent || (
                  !hoverString.endsWith(
                    expressionType
                  ) && !symbol.isType && !symbol.flags.isAllOf(EnumCase)
                )
              val content = HoverMarkup(
                expressionType,
                hoverString,
                docString,
                forceExpressionType
              )
              ju.Optional.of(new Hover(content.toMarkupContent))
            case _ =>
              ju.Optional.empty
          end match
    end if
  end hover

  extension (pos: SourcePosition)
    private def isPoint: Boolean = pos.start == pos.end

  private def qual(tree: Tree): Tree =
    tree match
      case Apply(q, _) => qual(q)
      case TypeApply(q, _) => qual(q)
      case AppliedTypeTree(q, _) => qual(q)
      case Select(q, _) => q
      case _ => tree

  private def seenFrom(tree: Tree, sym: Symbol)(using Context): (Type, Symbol) =
    try
      val pre = qual(tree)
      val denot = sym.denot.asSeenFrom(pre.tpe.widenTermRefExpr)
      (denot.info, sym.withUpdatedTpe(denot.info))
    catch case NonFatal(e) => (sym.info, sym)

  private def expandRangeToEnclosingApply(
      path: List[Tree],
      pos: SourcePosition
  )(using Context): List[Tree] =
    def tryTail(enclosing: List[Tree]): Option[List[Tree]] =
      enclosing match
        case Nil => None
        case head :: tail =>
          head match
            case t: GenericApply
                if t.fun.srcPos.span.contains(pos.span) && !t.tpe.isErroneous =>
              tryTail(tail).orElse(Some(enclosing))
            case New(_) =>
              tail match
                case Nil => None
                case Select(_, _) :: next =>
                  tryTail(next)
                case _ =>
                  None
            case _ =>
              None
    path match
      case head :: tail =>
        tryTail(tail).getOrElse(path)
      case _ =>
        List(EmptyTree)
  end expandRangeToEnclosingApply
end HoverProvider
