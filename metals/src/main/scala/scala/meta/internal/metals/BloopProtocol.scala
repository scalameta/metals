package scala.meta.internal.metals

import scala.util.Properties

final class BloopProtocol(value: String) {
  def isAuto: Boolean = value == "auto"
  def isTcp: Boolean = value == "tcp" || (isAuto && Properties.isWin)
  def isNamedPipe: Boolean = value == "named-pipe"
  def isUnix: Boolean = value == "unix"
}
object BloopProtocol {
  def auto: BloopProtocol = new BloopProtocol("auto")
  def tcp: BloopProtocol = new BloopProtocol("tcp")
  def default: BloopProtocol =
    new BloopProtocol(System.getProperty("metals.bloop-protocol", "auto"))
}
