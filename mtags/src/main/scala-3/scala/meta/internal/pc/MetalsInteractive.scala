package scala.meta.internal.pc

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.ContextOps._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.CyclicReference
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.interactive.Interactive

object MetalsInteractive {

  // This is a copy of dotty.tools.dotc.Interactive.contextOfPath
  // with a minor fix in how it processes `Template`
  //
  // Might be removed after merging https://github.com/lampepfl/dotty/pull/12783
  def contextOfPath(path: List[Tree])(using Context): Context = path match {
    case Nil | _ :: Nil =>
      ctx.fresh
    case nested :: encl :: rest =>
      val outer = contextOfPath(encl :: rest)
      try
        encl match {
          case tree @ PackageDef(pkg, stats) =>
            assert(tree.symbol.exists)
            if (nested `eq` pkg) outer
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
            if ((constr :: self :: parents).contains(nested)) outer
            else
              contextOfStat(
                tree.body,
                nested,
                tree.symbol,
                outer.inClassContext(self.symbol)
              )
          case _ =>
            outer
        }
      catch {
        case ex: CyclicReference => outer
      }
  }

  def contextOfStat(
      stats: List[Tree],
      stat: Tree,
      exprOwner: Symbol,
      ctx: Context
  ): Context = stats match {
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
  }
}
