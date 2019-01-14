package scala.meta.internal.metals

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
