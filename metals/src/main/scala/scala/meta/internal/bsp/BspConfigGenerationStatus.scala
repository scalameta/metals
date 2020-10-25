package scala.meta.internal.bsp

import scala.meta.internal.process.ExitCodes

object BspConfigGenerationStatus {
  type ExitCode = Int
  type Message = String
  sealed trait BspConfigGenerationStatus
  case object Generated extends BspConfigGenerationStatus
  case object Cancelled extends BspConfigGenerationStatus

  /**
   * This is special cased as an Either to account for a build tool trying to
   * generate the bsp config and failing, thus returning an exit code, but also
   * used in the case where we can see that the build tool is maybe too old,
   * and therefore we can just fail right away and return a Failed with the
   * message that it's too old.
   */
  case class Failed(reason: Either[ExitCode, Message])
      extends BspConfigGenerationStatus

  def fromExitCode(exit: Int): BspConfigGenerationStatus = exit match {
    case ExitCodes.Success => Generated
    case ExitCodes.Cancel => Cancelled
    case result => Failed(Left(result))
  }
}
