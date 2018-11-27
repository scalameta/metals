package scala.meta.internal.metals

sealed abstract class Confirmation {
  import Confirmation._
  def isYes: Boolean = this == Yes
  def isNo: Boolean = this == No
}
object Confirmation {
  def fromBoolean(isYes: Boolean): Confirmation =
    if (isYes) Yes else No
  case object Yes extends Confirmation
  case object No extends Confirmation
}
