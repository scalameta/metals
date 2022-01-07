package scala.meta.internal.parsing

import scala.meta.tokens.Token

/**
 * A pair of tokens that align with each other across two different files
 */
class MatchingToken(
    val original: TokenEditDistance.BasicToken,
    val revised: TokenEditDistance.BasicToken
) {
  override def toString: String =
    (original, revised) match {
      case (org: Token, rev: Token) =>
        s"${org.structure} <-> ${rev.structure}"
      case (org: JavaToken, rev: JavaToken) =>
        s"[${org.id}] <-> [${rev.id}]"
      case _ =>
        ""
    }

}
