package scala.meta.internal.builds

import scala.meta.internal.builds.Digest.Status

sealed abstract class BloopInstallResult extends Product with Serializable {
  import BloopInstallResult._
  def name: String =
    this match {
      case Duplicate(status) => status.toString
      case _ => this.toString
    }
  def isInstalled: Boolean = this == Installed
  def isFailed: Boolean = this.isInstanceOf[Failed]
  def toChecksumStatus: Option[Status] =
    Option(this).collect {
      case BloopInstallResult.Rejected => Status.Rejected
      case BloopInstallResult.Installed => Status.Installed
      case BloopInstallResult.Cancelled => Status.Cancelled
      case Failed(_) => Status.Failed
    }
}
object BloopInstallResult {
  case object Dismissed extends BloopInstallResult
  case class Duplicate(status: Status) extends BloopInstallResult
  case object Rejected extends BloopInstallResult
  case object Unchanged extends BloopInstallResult
  case object Installed extends BloopInstallResult
  case object Cancelled extends BloopInstallResult
  case class Failed(exit: Int) extends BloopInstallResult
}
