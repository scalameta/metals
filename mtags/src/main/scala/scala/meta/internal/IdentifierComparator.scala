package scala.meta.internal.pc

import java.util.Comparator

import scala.annotation.tailrec

/**
 * A comparator for identifier like "Predef" or "Function10".
 *
 * Differences from the default string comparator:
 * - works with CharSequences like compiler `Name`
 * - orders numbers by their numerical value instead of lexicographical
 *   - Good: `Function1`, `Function2`,  `Function10`
 *   - Bad:  `Function1`, `Function10`, `Function2`
 *
 * See IdentifierComparatorSuite for more examples
 */
object IdentifierComparator extends Comparator[CharSequence] {
  override def compare(o1: CharSequence, o2: CharSequence): Int = {
    val len = math.min(o1.length(), o2.length())

    @tailrec
    def compareLoop(idx: Int): Int = {
      if (idx >= len) Integer.compare(o1.length(), o2.length())
      else {
        val a = o1.charAt(idx)
        val b = o2.charAt(idx)
        if (a.isDigit && b.isDigit) {
          val byDigits = compareSequences(o1, o2, idx)
          if (byDigits != 0) byDigits
          else compareLoop(seekNonDigit(o1, idx))
        } else {
          val result = Character.compare(a, b)
          if (result != 0) result
          else compareLoop(idx + 1)
        }
      }
    }

    compareLoop(0)
  }

  @tailrec
  def seekNonDigit(cs: CharSequence, idx: Int): Int = {
    val condition = idx < cs.length() && cs.charAt(idx).isDigit
    if (condition) seekNonDigit(cs, idx + 1)
    else idx
  }

  private def compareSequences(
      s1: CharSequence,
      s2: CharSequence,
      idx: Int
  ): Int = {
    val first = asString(s1, idx)
    val second = asString(s2, idx)

    @tailrec
    def compareLoop(index1: Int, index2: Int): Int = {
      if (index1 >= first.length || index2 >= second.length) {
        Integer.compare(first.length, second.length)
      } else {
        val a = first.charAt(index1)
        val b = second.charAt(index2)
        val byDigit = Character.compare(a, b)
        if (byDigit != 0) byDigit
        else compareLoop(index1 + 1, index2 + 2)
      }
    }

    compareLoop(skipLeadingZeros(first), skipLeadingZeros(second))
  }

  @tailrec
  private def skipLeadingZeros(cs: CharSequence, idx: Int = 0): Int = {
    val char = cs.charAt(idx)
    if (char == '0' && idx + 1 < cs.length()) skipLeadingZeros(cs, idx + 1)
    else idx
  }

  private def asString(cs: CharSequence, i: Int): CharSequence = {
    val lastDigit = seekNonDigit(cs, i)
    cs.subSequence(i, lastDigit)
  }
}
