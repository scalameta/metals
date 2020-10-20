package scala.meta.internal.builds

import scala.meta.internal.builds.Digest.Status

/**
 * Represents the the status of a `workspace/reload` or a `bloopInstall` request.
 */
sealed abstract class WorkspaceReloadStatus extends Product with Serializable {
  import WorkspaceReloadStatus._
  def name: String =
    this match {
      case Duplicate(status) => status.toString
      case _ => this.toString
    }
  def isInstalled: Boolean = this == Installed
  def isFailed: Boolean = this.isInstanceOf[Failed]
  def toChecksumStatus: Option[Status] =
    Option(this).collect {
      case WorkspaceReloadStatus.Rejected => Status.Rejected
      case WorkspaceReloadStatus.Installed => Status.Installed
      case WorkspaceReloadStatus.Cancelled => Status.Cancelled
      case Failed(_) => Status.Failed
    }
}
object WorkspaceReloadStatus {
  case object Dismissed extends WorkspaceReloadStatus
  case class Duplicate(status: Status) extends WorkspaceReloadStatus
  case object Rejected extends WorkspaceReloadStatus
  case object Unchanged extends WorkspaceReloadStatus
  case object Installed extends WorkspaceReloadStatus
  case object Cancelled extends WorkspaceReloadStatus
  case class Failed(exit: Int) extends WorkspaceReloadStatus
}
