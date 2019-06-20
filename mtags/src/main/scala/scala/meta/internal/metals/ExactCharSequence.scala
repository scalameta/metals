package scala.meta.internal.metals

/**
 * A char sequence that represents an exact symbol search.
 *
 * For example, `ExactSymbolSearch("S")` is added to a bloom filter
 * to indicate a souce file has a symbol with the exact name "S".
 */
case class ExactCharSequence(value: CharSequence) extends CharSequence {
  override def length(): Int = value.length() + 1

  override def charAt(index: Int): Char = {
    if (index == 0) {
      // NOTE(olafur): Magical character to customize the hash value of
      // this charsequence when adding it to a bloom filter.
      12345.toChar
    } else {
      value.charAt(index - 1)
    }
  }

  override def subSequence(start: Int, end: Int): CharSequence = {
    throw new UnsupportedOperationException()
  }
}
