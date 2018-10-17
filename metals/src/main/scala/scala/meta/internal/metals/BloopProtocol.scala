package scala.meta.internal.metals

import scala.meta.internal.metals.BloopProtocol._
import scala.util.Properties

sealed abstract class BloopProtocol extends Product with Serializable {
  def isAuto: Boolean = this == Auto
  def isTcp: Boolean = this == Tcp || (isAuto && Properties.isWin)
  def isUnix: Boolean = this == Unix
}
object BloopProtocol {
  case object Auto extends BloopProtocol
  case object Tcp extends BloopProtocol
  case object Unix extends BloopProtocol
  def all: List[BloopProtocol] = List(Auto, Tcp, Unix)
}
