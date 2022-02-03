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
    val first = toDigit(s1, idx)
    val second = toDigit(s2, idx)
    first.compare(second)
  }

  private def toDigit(cs: CharSequence, i: Int): BigInt = {
    val digit = cs.subSequence(i, seekNonDigit(cs, i))
    BigInt(digit.toString())
  }
}
