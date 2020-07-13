package scala.meta.internal.metals.config

object StatusBarState {
  sealed trait StatusBarState
  case object On extends StatusBarState
  case object Off extends StatusBarState
  case object ShowMessage extends StatusBarState
  case object LogMessage extends StatusBarState

  def fromString(value: String): Option[StatusBarState] =
    value match {
      case "on" => Some(On)
      case "off" => Some(Off)
      case "show-message" => Some(ShowMessage)
      case "log-message" => Some(LogMessage)
      case _ => None
    }

}
