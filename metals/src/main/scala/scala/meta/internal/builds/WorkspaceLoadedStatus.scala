package scala.meta.internal.builds

import scala.meta.internal.builds.Digest.Status

/**
 * Represents the the status of a `workspace/reload` or a `bloopInstall` request.
 */
sealed abstract class WorkspaceLoadedStatus extends Product with Serializable {
  import WorkspaceLoadedStatus._
  def name: String =
    this match {
      case Duplicate(status) => status.toString
      case _ => this.toString
    }
  def isInstalled: Boolean = this == Installed
  def isFailed: Boolean = this.isInstanceOf[Failed]
  def toChecksumStatus: Option[Status] =
    Option(this).collect {
      case WorkspaceLoadedStatus.Rejected => Status.Rejected
      case WorkspaceLoadedStatus.Installed => Status.Installed
      case WorkspaceLoadedStatus.Cancelled => Status.Cancelled
      case Failed(_) => Status.Failed
    }
}
object WorkspaceLoadedStatus {
  case object Dismissed extends WorkspaceLoadedStatus
  case class Duplicate(status: Status) extends WorkspaceLoadedStatus
  case object Rejected extends WorkspaceLoadedStatus
  case object Unchanged extends WorkspaceLoadedStatus
  case object Installed extends WorkspaceLoadedStatus
  case object Cancelled extends WorkspaceLoadedStatus
  case class Failed(exit: Int) extends WorkspaceLoadedStatus
}
