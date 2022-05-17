package scala.meta.internal.metals.codeactions

protected final case class Indentation(
    indentation: String = "",
    maxValue: Boolean = false
) extends PartiallyOrdered[Indentation] {
  require(indentation.forall(char => char.isSpaceChar || char == '\t'))

  override def tryCompareTo[B >: Indentation](
      that: B
  )(implicit evidence$1: AsPartiallyOrdered[B]): Option[Int] = {
    val thatMaxValue = that.asInstanceOf[Indentation].maxValue
    if (maxValue) {
      if (thatMaxValue)
        Some(0)
      else Some(1)
    } else if (thatMaxValue) Some(-1)
    else {
      val thatIndentation = that.asInstanceOf[Indentation].indentation
      if (indentation.startsWith(thatIndentation)) {
        if (indentation.length > thatIndentation.length)
          Some(1)
        else Some(0)
      } else if (
        thatIndentation.startsWith(
          indentation
        ) && thatIndentation.length > indentation.length
      )
        Some(-1)
      else None
    }

  }
}
