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
    val inferredArgBegOpt = {
      val index = text.drop(prevArgEnd).indexOf('(')
      if (index < 0) None
      else Some(index + prevArgEnd + 1)
    }
    inferredArgBegOpt match {
      case None => argBeg
      case Some(inferredArgBeg) if inferredArgBeg == argBeg => argBeg
      case Some(inferredArgBeg) =>
        val slice =
          text.drop(inferredArgBeg).take(argBeg - inferredArgBeg)
        List(slice.indexOf('('), slice.indexOf('{'))
          .filter(_ >= 0)
          .sorted match {
          case index :: _ => inferredArgBeg + index
          case Nil => argBeg
        }
    }
  }
}
