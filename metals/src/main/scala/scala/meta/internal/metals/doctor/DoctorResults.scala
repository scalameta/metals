package scala.meta.internal.metals.doctor

import scala.meta.internal.metals.Icons

import ujson.Obj

final case class DoctorResults(
    title: String,
    header: DoctorHeader,
    messages: Option[List[DoctorMessage]],
    targets: Option[Seq[DoctorTargetInfo]],
    explanations: List[Obj],
) {
  def toJson: Obj = {
    val json = ujson.Obj(
      "title" -> title,
      "header" -> header.toJson,
      "version" -> DoctorResults.version,
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
  val version = 3
}

final case class DoctorMessage(title: String, recommendations: List[String]) {
  def toJson: Obj =
    ujson.Obj(
      "title" -> title,
      "recommendations" -> recommendations,
    )
}

sealed case class DoctorStatus(explanation: String, isCorrect: Boolean)
object DoctorStatus {
  object check extends DoctorStatus(Icons.unicode.check, true)
  object alert extends DoctorStatus(Icons.unicode.alert, false)
  object error extends DoctorStatus(Icons.unicode.error, false)
  object info extends DoctorStatus(Icons.unicode.info, false)
}

final case class DoctorTargetInfo(
    name: String,
    gotoCommand: String,
    dataKind: String,
    baseDirectory: String,
    targetType: String,
    compilationStatus: DoctorStatus,
    diagnosticsStatus: DoctorStatus,
    interactiveStatus: DoctorStatus,
    indexesStatus: DoctorStatus,
    debuggingStatus: DoctorStatus,
    javaStatus: DoctorStatus,
    recommenedFix: String,
) {
  def toJson: Obj =
    ujson.Obj(
      "buildTarget" -> name,
      "gotoCommand" -> gotoCommand,
      "compilationStatus" -> compilationStatus.explanation,
      "targetType" -> targetType,
      "diagnostics" -> diagnosticsStatus.explanation,
      "interactive" -> interactiveStatus.explanation,
      "semanticdb" -> indexesStatus.explanation,
      "debugging" -> debuggingStatus.explanation,
      "java" -> javaStatus.explanation,
      "recommendation" -> recommenedFix,
    )

}

/**
 * @param buildTool if Metals detected multiple build tools, this specifies
 *        the one the user has chosen
 * @param buildServer the build server that is being used
 * @param importBuildStatus if the user has turned the import prompt off, this
 *        will include a message on how to get it back.
 * @param jdkInfo java version and location information
 * @param serverInfo the version of the server that is being used
 * @param buildTargetDescription small description on what a build target is
 */
final case class DoctorHeader(
    buildTool: Option[String],
    buildServer: String,
    importBuildStatus: Option[String],
    jdkInfo: Option[String],
    serverInfo: String,
    buildTargetDescription: String,
) {
  def toJson: Obj = {
    val base =
      ujson.Obj(
        "buildServer" -> buildServer,
        "serverInfo" -> serverInfo,
        "buildTargetDescription" -> buildTargetDescription,
      )

    buildTool.foreach { bt => base.update("buildTool", bt) }
    importBuildStatus.foreach { ibs => base.update("importBuildStatus", ibs) }
    jdkInfo.foreach { jdki => base.update("jdkInfo", jdki) }
    base
  }
}
