package scala.meta.internal.pc

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.ContextOps.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.CyclicReference
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.SourceTree
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition

object MetalsInteractive:

  // This is a copy of dotty.tools.dotc.Interactive.contextOfPath
  // with a minor fix in how it processes `Template`
  //
  // Might be removed after merging https://github.com/lampepfl/dotty/pull/12783
  def contextOfPath(path: List[Tree])(using Context): Context = path match
    case Nil | _ :: Nil =>
      ctx.fresh
    case nested :: encl :: rest =>
      val outer = contextOfPath(encl :: rest)
      try
        encl match
          case tree @ PackageDef(pkg, stats) =>
            assert(tree.symbol.exists)
            if nested `eq` pkg then outer
            else
              contextOfStat(
                stats,
                nested,
                pkg.symbol.moduleClass,
                outer.packageContext(tree, tree.symbol)
              )
          case tree: DefDef =>
            assert(tree.symbol.exists)
            val localCtx = outer.localContext(tree, tree.symbol).setNewScope
            for params <- tree.paramss; param <- params do
              localCtx.enter(param.symbol)
            // Note: this overapproximates visibility a bit, since value parameters are only visible
            // in subsequent parameter sections
            localCtx
          case tree: MemberDef =>
            assert(tree.symbol.exists)
            outer.localContext(tree, tree.symbol)
          case tree @ Block(stats, expr) =>
            val localCtx = outer.fresh.setNewScope
            stats.foreach {
              case stat: MemberDef => localCtx.enter(stat.symbol)
              case _ =>
            }
            contextOfStat(stats, nested, ctx.owner, localCtx)
          case tree @ CaseDef(pat, guard, rhs) if nested `eq` rhs =>
            val localCtx = outer.fresh.setNewScope
            pat.foreachSubTree {
              case bind: Bind => localCtx.enter(bind.symbol)
              case _ =>
            }
            localCtx
          case tree @ Template(constr, parents, self, _) =>
            if (constr :: self :: parents).contains(nested) then outer
            else
              contextOfStat(
                tree.body,
                nested,
                tree.symbol,
                outer.inClassContext(self.symbol)
              )
          case _ =>
            outer
      catch case ex: CyclicReference => outer
      end try

  def contextOfStat(
      stats: List[Tree],
      stat: Tree,
      exprOwner: Symbol,
      ctx: Context
  ): Context = stats match
    case Nil =>
      ctx
    case first :: _ if first eq stat =>
      ctx.exprContext(stat, exprOwner)
    case (imp: Import) :: rest =>
      contextOfStat(
        rest,
        stat,
        exprOwner,
        ctx.importContext(imp, inContext(ctx) { imp.symbol })
      )
    case _ :: rest =>
      contextOfStat(rest, stat, exprOwner, ctx)

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
  def isOnName(
      path: List[Tree],
      sourcePos: SourcePosition,
      source: SourceFile
  )(using Context): Boolean =
    def contains(tree: Tree): Boolean = tree match
      case select: Select =>
        // using `nameSpan` as SourceTree for Select (especially symbolic-infix e.g. `::` of `1 :: Nil`) miscalculate positions
        select.nameSpan.contains(sourcePos.span)
      case tree: NameTree =>
        SourceTree(tree, source).namePos.contains(sourcePos)
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
  end isOnName

  private lazy val isForName: Set[Name] = Set[Name](
    StdNames.nme.map,
    StdNames.nme.withFilter,
    StdNames.nme.flatMap,
    StdNames.nme.foreach
  )
  def isForSynthetic(gtree: Tree)(using Context): Boolean =
    def isForComprehensionSyntheticName(select: Select): Boolean =
      select.sourcePos.toSynthetic == select.qualifier.sourcePos.toSynthetic && isForName(
        select.name
      )
    gtree match
      case Apply(fun, List(_: Block)) => isForSynthetic(fun)
      case TypeApply(fun, _) => isForSynthetic(fun)
      case gtree: Select if isForComprehensionSyntheticName(gtree) => true
      case _ => false

end MetalsInteractive
