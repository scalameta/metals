package scala.meta.internal.metals.config

object RunType {
  sealed trait RunType
  case object Run extends RunType
  case object RunOrTestFile extends RunType
  case object TestFile extends RunType
  case object TestTarget extends RunType
  case object RunClosest extends RunType

  def fromString(string: String): Option[RunType] = {
    string match {
      case "run" => Some(Run)
      case "runOrTestFile" => Some(RunOrTestFile)
      case "testFile" => Some(TestFile)
      case "testTarget" => Some(TestTarget)
      case "runClosest" => Some(RunClosest)
      case _ => None
    }
  }

  case class UnknownRunTypeException(runType: String)
      extends Exception(s"Received invalid runType: ${runType}.")
}
