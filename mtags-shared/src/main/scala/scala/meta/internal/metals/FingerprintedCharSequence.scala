package scala.meta.internal.metals

/**
 * A zero-copy char sequences that adds the fingerprint character at the start of the string.
 *
 * The purpose of this class is to support special queries like "find all
 * symbols that have the prefix `Fil`".  To do this, we insert all three
 * possible prefixes into a filter:
 * - "${prefix_fingerprint}F"
 * - "${prefix_fingerprint}Fi"
 * - "${prefix_fingerprint}Fil" At query time, we test if a bloom filter
 * contains the fingerprint knowing that if it returns true, it contains a
 * symbol that actually starts with "Fil" and not, for example, "AbsoluteFile".
 *
 * Sidenote, it's not a quadratic operation to insert all the prefixes, we do
 * with a linear loop where we compute rolling hash.
 */
case class FingerprintedCharSequence(value: CharSequence, fingerprint: Char)
    extends CharSequence {
  override def length(): Int = value.length() + 1

  override def charAt(index: Int): Char = {
    if (index == 0) {
      fingerprint
    } else {
      value.charAt(index - 1)
    }
  }

  override def subSequence(start: Int, end: Int): CharSequence = {
    throw new UnsupportedOperationException()
  }
}

object FingerprintedCharSequence {
  def exactWord(value: CharSequence): FingerprintedCharSequence =
    FingerprintedCharSequence(value, "😎".charAt(0))
}
