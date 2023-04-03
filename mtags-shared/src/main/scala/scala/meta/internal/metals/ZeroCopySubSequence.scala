package scala.meta.internal.metals

class ZeroCopySubSequence(underlying: CharSequence, start: Int, end: Int)
    extends CharSequence {
  override def length(): Int = end - start

  override def charAt(index: Int): Char = {
    underlying.charAt(start + index)
  }

  override def subSequence(newStart: Int, newEnd: Int): CharSequence = {
    new ZeroCopySubSequence(underlying, start + newStart, start + end)
  }

  override def toString: String = underlying.subSequence(start, end).toString
}
