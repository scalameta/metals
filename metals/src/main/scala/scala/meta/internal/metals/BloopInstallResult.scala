package scala.meta.internal.metals

import scala.meta.internal.metals.SbtChecksum.Status

sealed abstract class BloopInstallResult extends Product with Serializable {
  import BloopInstallResult._
  def isInstalled: Boolean = this == Installed
  def isFailed: Boolean = this.isInstanceOf[Failed]
  def toChecksumStatus: Option[Status] = Option(this).collect {
    case BloopInstallResult.Rejected => Status.Rejected
    case BloopInstallResult.Installed => Status.Installed
    case BloopInstallResult.Cancelled => Status.Cancelled
    case Failed(_) => Status.Failed
  }
}
object BloopInstallResult {
  case object Rejected extends BloopInstallResult
  case object Unchanged extends BloopInstallResult
  case object Installed extends BloopInstallResult
  case object Cancelled extends BloopInstallResult
  case class Failed(exit: Int) extends BloopInstallResult
}
