package scala.meta.internal.metals

/**
 * A char sequence that represents an exact symbol search.
 *
 * For example, `PrefixCharSequence("S")` is added to a bloom filter
 * to indicate a source file has a symbol starting with "S".
 */
case class PrefixCharSequence(value: CharSequence) extends CharSequence {
  override def length(): Int = value.length() + 1

  override def charAt(index: Int): Char = {
    if (index == value.length()) {
      // NOTE(olafur): Magical character to customize the hash value of
      // this charsequence when adding it to a bloom filter.
      12345.toChar
    } else {
      value.charAt(index)
    }
  }

  override def subSequence(start: Int, end: Int): CharSequence = {
    throw new UnsupportedOperationException()
  }
}
