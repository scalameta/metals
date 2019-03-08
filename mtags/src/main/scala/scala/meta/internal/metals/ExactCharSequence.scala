package scala.meta.internal.metals

case class ExactCharSequence(value: CharSequence) extends CharSequence {
  override def length(): Int = value.length() + 1

  override def charAt(index: Int): Char = {
    if (index == 0) 12345.toChar
    else value.charAt(index + 1)
  }

  override def subSequence(start: Int, end: Int): CharSequence = {
    ExactCharSequence(value.subSequence(start + 1, end + 1))
  }
}
