package scala.meta.internal.bsp

import scala.meta.internal.process.ExitCodes

object BspConfigGenerationStatus {
  type ExitCode = Int
  type Message = String
  sealed trait BspConfigGenerationStatus
  case object Generated extends BspConfigGenerationStatus
  case object Cancelled extends BspConfigGenerationStatus
  case class Failed(reason: Either[ExitCode, Message])
      extends BspConfigGenerationStatus

  def fromExitCode(exit: Int): BspConfigGenerationStatus = exit match {
    case ExitCodes.Success => Generated
    case ExitCodes.Cancel => Cancelled
    case result => Failed(Left(result))
  }
}
