package scala.meta.internal.pc

import scala.annotation.tailrec

class ConvertToNamedArgumentsUtils(text: String) {
  @tailrec
  final def findActualArgEnd(argEnd: Int, argsEnd: Int): Int =
    if (argEnd == argsEnd || text.charAt(argEnd) == ',') argEnd
    else findActualArgEnd(argEnd + 1, argsEnd)

  /**
   * Accounts for additional parenthesis around arguments:
   *  e.g. f((a -> b))
   */
  def findActualArgBeginning(prevArgEnd: Int, argBeg: Int): Int = {
    val inferredArgBeg = prevArgEnd + 1
    if (inferredArgBeg == argBeg) argBeg
    else {
      val slice =
        text.drop(inferredArgBeg).take(argBeg - inferredArgBeg)
      val parenIndex = slice.indexOf('(')
      if (parenIndex < 0) argBeg
      else inferredArgBeg + parenIndex
    }
  }
}
