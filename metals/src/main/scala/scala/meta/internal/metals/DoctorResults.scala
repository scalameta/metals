package scala.meta.internal.metals

import upickle.default.{ReadWriter => RW, macroRW}

final case class DoctorResults(
    title: String,
    headerText: String,
    messages: Option[List[DoctorMessage]],
    targets: Option[List[DoctorTargetInfo]]
)

object DoctorResults {
  implicit val rw: RW[DoctorResults] = macroRW
}

final case class DoctorMessage(title: String, recommendations: List[String])

object DoctorMessage {
  implicit val rw: RW[DoctorMessage] = macroRW
}

final case class DoctorTargetInfo(
    name: String,
    scalaVersion: String,
    definitionStatus: String,
    completionsStatus: String,
    referencesStatus: String,
    recommenedFix: String
)

object DoctorTargetInfo {
  implicit val rw: RW[DoctorTargetInfo] = macroRW
}
