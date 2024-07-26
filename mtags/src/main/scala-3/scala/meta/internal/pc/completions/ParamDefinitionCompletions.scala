package scala.meta.internal.pc.completions

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.Context

object ParamDefinitionCompletions:
  private val possibleKeywords = List("using", "implicit")
  private def completion(keyword: String) = CompletionValue.keyword(keyword, keyword ++ " ")

  def unapply(path: List[Tree])(using Context): Option[Boolean] =
    path match
      case (vd: ValDef) :: (d : DefDef) :: _ =>
        d.paramss.lastOption.map(_.indexWhere(_.span.contains(vd.span))).filter(_ >= 0)
         match
          case Some(ind) if ind == 0 => Some(true)
          case Some(_) => Some(false)
          case _ => None
      case _ => None

  def contribute(allowKeywords: Boolean, pos: CompletionPos): List[CompletionValue] =
    if allowKeywords
    then possibleKeywords.filter(_.startsWith(pos.query)).map(completion)
    else Nil
