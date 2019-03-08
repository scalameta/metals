package scala.meta.internal.metals

/**
 * A CharSequence which is the concatenation of two strings.
 *
 * This class is the equivalent of doing `string1 + string2` but in constant time
 * and with no copying.
 */
class ConcatSequence(val a: CharSequence, val b: CharSequence)
    extends CharSequence {
  override def length(): Int = a.length + b.length
  override def charAt(index: Int): Char = {
    if (index < a.length) a.charAt(index)
    else b.charAt(index - a.length())
  }
  override def subSequence(start: Int, end: Int): CharSequence = ???
  override def toString: String = s"$a$b"
}
