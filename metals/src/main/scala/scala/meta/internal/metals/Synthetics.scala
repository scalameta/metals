package scala.meta.internal.metals

import scala.meta.internal.semanticdb.Synthetic

/**
 * Utilities to work with SemanticDB synthetics.
 */
object Synthetics {
  def existsSymbol(synthetic: Synthetic)(fn: String => Boolean): Boolean = {
    foreachSymbol(synthetic) { symbol =>
      if (fn(symbol)) Stop
      else Continue
    }.isStop
  }

  sealed abstract class ForeachResult {
    def isStop: Boolean = this == Stop
  }
  case object Continue extends ForeachResult
  case object Stop extends ForeachResult

  def foreachSymbol(
      synthetic: Synthetic
  )(fn: String => ForeachResult): ForeachResult = {
    import scala.meta.internal.semanticdb._
    def isStop(t: Tree): Boolean =
      t match {
        case ApplyTree(function, arguments) =>
          isStop(function) || arguments.exists(isStop)
        case SelectTree(qualifier, id) =>
          id.exists(isStop)
        case IdTree(symbol) =>
          fn(symbol).isStop
        case TypeApplyTree(function, _) =>
          isStop(function)
        case FunctionTree(_, body) =>
          isStop(body)
        case LiteralTree(_) =>
          false
        case MacroExpansionTree(_, _) =>
          false
        case OriginalTree(_) => false
        case Tree.Empty => false
      }
    if (isStop(synthetic.tree)) Stop
    else Continue
  }
}
