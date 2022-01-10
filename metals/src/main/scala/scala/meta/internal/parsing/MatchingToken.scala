package scala.meta.internal.parsing

import scala.meta.tokens.Token

/**
 * A pair of tokens that align with each other across two different files
 */
class MatchingToken[A](val original: A, val revised: A) {
  override def toString: String =
    (original, revised) match {
      case (org: Token, rev: Token) =>
        s"${org.structure} <-> ${rev.structure}"
      case (org: JavaToken, rev: JavaToken) =>
        s"[${org.id} (${org.text})] <-> [${rev.id} (${rev.text})]"
      case _ =>
        ""
    }

}
