package scala.meta.internal.metals

import scala.meta.Token

/**
 * A pair of tokens that align with each other across two different files */
class MatchingToken(val original: Token, val revised: Token) {
  override def toString: String =
    s"${original.structure} <-> ${revised.structure}"
}
