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
          val byDigits = compareDigitSequences(o1, o2, idx)
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
  private def seekNonDigit(cs: CharSequence, idx: Int): Int = {
    val condition = idx < cs.length() && cs.charAt(idx).isDigit
    if (condition) seekNonDigit(cs, idx + 1)
    else idx
  }

  /**
   * Compare two digit sequences:
   * - compare their length if they aren't equal
   * - compare them digit by digit, short circuit for first diff
   * - for same sequences compare leading zeros
   */
  private def compareDigitSequences(
      s1: CharSequence,
      s2: CharSequence,
      start: Int
  ): Int = {
    val (first, zeros1) = extractDigits(s1, start)
    val (second, zeros2) = extractDigits(s2, start)

    val len = math.min(first.length, second.length)

    @tailrec
    def compareLoop(index: Int): Int = {
      if (index >= len) {
        Integer.compare(zeros1, zeros2)
      } else {
        val a = first.charAt(index)
        val b = second.charAt(index)
        val byDigit = Character.compare(a, b)
        // if digits are the same keep comparing
        if (byDigit == 0) compareLoop(index + 1)
        else byDigit
      }
    }

    val isFirstLonger = Integer.compare(first.length, second.length)

    if (isFirstLonger != 0) isFirstLonger
    else compareLoop(0)
  }

  @tailrec
  private def skipLeadingZeros(cs: CharSequence, index: Int): Int = {
    val char = cs.charAt(index)
    if (char == '0' && index + 1 < cs.length()) skipLeadingZeros(cs, index + 1)
    else index
  }

  /**
   * @param cs CharSequence from which digits will be extracted
   * @param start index of first digit character
   * @return Tuple containing extracted digits and stripped, leading zeros
   */
  private def extractDigits(
      cs: CharSequence,
      start: Int
  ): (CharSequence, Int) = {
    val firstDigit = skipLeadingZeros(cs, start)
    val lastDigit = seekNonDigit(cs, firstDigit)
    val digits = cs.subSequence(firstDigit, lastDigit)
    val leadingZeros = firstDigit - start
    (digits, leadingZeros)
  }
}
