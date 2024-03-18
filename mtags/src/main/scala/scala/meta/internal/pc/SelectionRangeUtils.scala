package scala.meta.internal.pc

import scala.meta.tokens.Token
import scala.meta.tokens.Token.Comment

object SelectionRangeUtils {

  /**
   * common part for scala 2/3 for selection expansion in comment
   *
   * @param tokenList
   * @param cursorStart
   * @param offsetStart
   * @return
   */
  def commentRangesFromTokens(
      tokenList: List[Token],
      cursorStart: Int,
      offsetStart: Int
  ): List[(Int, Int)] = {
    val cursorStartShifted = cursorStart - offsetStart

    tokenList
      .collect { case x: Comment =>
        (x.start, x.end, x.pos)
      }
      .collect {
        case (commentStart, commentEnd, _)
            if commentStart <= cursorStartShifted && cursorStartShifted <= commentEnd =>
          (commentStart + offsetStart, commentEnd + offsetStart)

      }
  }
}
