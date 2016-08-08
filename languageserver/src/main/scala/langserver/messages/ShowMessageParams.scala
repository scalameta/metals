package langserver.messages

case class ShowMessageParams(tpe: Long, message: String)

object MessageType {
  /** An error message. */
  final val Error = 1

  /** A warning message. */
  final val Warning = 2
  
  /** An information message. */
  final val Info = 3
  
  /** A log message. */
  final val Log = 4
}

