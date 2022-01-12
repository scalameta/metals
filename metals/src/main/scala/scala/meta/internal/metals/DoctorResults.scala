package scala.meta.internal.metals

import ujson.Obj

final case class DoctorResults(
    title: String,
    headerText: String,
    messages: Option[List[DoctorMessage]],
    targets: Option[Seq[DoctorTargetInfo]]
) {
  private val version = 1
  def toJson: Obj = {
    val json = ujson.Obj(
      "title" -> title,
      "headerText" -> headerText,
      "version" -> version
    )
    messages.foreach(messageList =>
      json("messages") = messageList.map(_.toJson)
    )
    targets.foreach(targetList => json("targets") = targetList.map(_.toJson))
    json
  }
}

final case class DoctorMessage(title: String, recommendations: List[String]) {
  def toJson: Obj =
    ujson.Obj(
      "title" -> title,
      "recommendations" -> recommendations
    )
}

sealed case class DoctorStatus(explanation: String, isCorrect: Boolean)
object DoctorStatus {
  object check extends DoctorStatus(Icons.unicode.check, true)
  object alert extends DoctorStatus(Icons.unicode.alert, false)
  object error extends DoctorStatus(Icons.unicode.error, false)
}
final case class DoctorTargetInfo(
    name: String,
    dataKind: String,
    baseDirectory: String,
    targetType: String,
    diagnosticsStatus: DoctorStatus,
    interactiveStatus: DoctorStatus,
    indexesStatus: DoctorStatus,
    debuggingStatus: DoctorStatus,
    javaStatus: DoctorStatus,
    recommenedFix: String
) {
  def toJson: Obj =
    ujson.Obj(
      "buildTarget" -> name,
      "targetType" -> targetType,
      "diagnostics" -> diagnosticsStatus.explanation,
      "interactive" -> interactiveStatus.explanation,
      "semanticdb" -> indexesStatus.explanation,
      "debugging" -> debuggingStatus.explanation,
      "java" -> javaStatus.explanation,
      "recommendation" -> recommenedFix
    )

}
