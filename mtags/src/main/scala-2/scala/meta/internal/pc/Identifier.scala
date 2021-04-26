package scala.meta.internal.pc

import scala.tools.nsc.Global

import scala.meta.internal.mtags.KeywordWrapper

object Identifier extends KeywordWrapper.Scala2 {
  def apply(name: Global#Name): String = backtickWrap(name)
  def apply(name: String): String = backtickWrap(name)
  def backtickWrap(name: Global#Name): String = {
    backtickWrap(name.decoded.trim)
  }
}
