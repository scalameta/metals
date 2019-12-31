package scala.meta.internal.metals

import ujson.Arr
import ujson.Obj

final case class DoctorResults(
    title: String,
    headerText: String,
    messages: Option[List[DoctorMessage]],
    targets: Option[List[DoctorTargetInfo]]
) {
  def toJson: Obj = {
    val json = ujson.Obj(
      "title" -> title,
      "headerText" -> headerText
    )
    messages.map(messageList => json("messages") = messageList.map(_.toJson))
    targets.map(targetList => json("targets") = targetList.map(_.toJson))
    json
  }
}

final case class DoctorMessage(title: String, recommendations: List[String]) {
  def toJson: Obj =
    ujson.Obj(
      "title" -> title,
      "recommendations" -> Arr(recommendations)
    )
}

final case class DoctorTargetInfo(
    name: String,
    scalaVersion: String,
    definitionStatus: String,
    completionsStatus: String,
    referencesStatus: String,
    recommenedFix: String
) {
  def toJson: Obj =
    ujson.Obj(
      "buildTarget" -> name,
      "scalaVersion" -> scalaVersion,
      "diagnostics" -> Icons.unicode.check,
      "gotoDefinition" -> definitionStatus,
      "completions" -> completionsStatus,
      "findReferences" -> referencesStatus,
      "recommendation" -> recommenedFix
    )
}
