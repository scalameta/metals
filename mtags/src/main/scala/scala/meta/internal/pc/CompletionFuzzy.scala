package scala.meta.internal.pc

import scala.meta.internal.metals.Fuzzy

/**
 * A custom version of fuzzy search designed for code completions.
 */
object CompletionFuzzy extends Fuzzy {

  // Don't treat classfile `$` or SemanticDB `/` as delimiters.
  override def isDelimiter(ch: Char): Boolean = false

}
