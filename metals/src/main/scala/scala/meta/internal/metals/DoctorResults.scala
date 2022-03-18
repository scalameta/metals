package scala.meta.internal.metals

import ujson.Obj

final case class DoctorResults(
    title: String,
    headerText: String,
    messages: Option[List[DoctorMessage]],
    targets: Option[Seq[DoctorTargetInfo]],
    explanations: List[Obj]
) {
  def toJson: Obj = {
    val json = ujson.Obj(
      "title" -> title,
      "headerText" -> headerText,
      "version" -> DoctorResults.version
    )
    messages.foreach(messageList =>
      json("messages") = messageList.map(_.toJson)
    )
    targets.foreach(targetList => json("targets") = targetList.map(_.toJson))
    json("explanations") = explanations
    json
  }
}

object DoctorResults {
  // Version of the Doctor json that is returned.
  val version = 2
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
    compilationStatus: DoctorStatus,
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
      "compilationStatus" -> compilationStatus.explanation,
      "targetType" -> targetType,
      "diagnostics" -> diagnosticsStatus.explanation,
      "interactive" -> interactiveStatus.explanation,
      "semanticdb" -> indexesStatus.explanation,
      "debugging" -> debuggingStatus.explanation,
      "java" -> javaStatus.explanation,
      "recommendation" -> recommenedFix
    )

}
