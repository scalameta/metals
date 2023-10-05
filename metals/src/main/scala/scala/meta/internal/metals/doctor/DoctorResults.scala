package scala.meta.internal.metals.doctor

import scala.meta.internal.metals.Icons

import ujson.Obj

final case class DoctorResults(
    title: String,
    header: DoctorHeader,
    folders: List[DoctorFolderResults],
) {
  def toJson: Obj =
    ujson.Obj(
      "title" -> title,
      "header" -> header.toJson,
      "folders" -> folders.map(_.toJson),
      "version" -> DoctorResults.version,
    )
}

object DoctorResults {
  // Version of the Doctor json that is returned.
  val version = 5
}

final case class DoctorFolderResults(
    folder: String,
    header: DoctorFolderHeader,
    messages: Option[List[DoctorMessage]],
    targets: Option[Seq[DoctorTargetInfo]],
    explanations: List[Obj],
    errorReports: List[ErrorReportInfo],
) {
  def toJson: Obj = {
    val json = ujson.Obj(
      "folder" -> folder,
      "header" -> header.toJson,
    )
    messages.foreach(messageList =>
      json("messages") = messageList.map(_.toJson)
    )
    targets.foreach(targetList => json("targets") = targetList.map(_.toJson))
    json("explanations") = explanations
    json("errorReports") = errorReports.map(_.toJson)
    json
  }
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

  def toMap(exclude: List[String] = List()): Map[String, String] =
    Map(
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
    ) -- exclude

}

/**
 * @param jdkInfo java version and location information
 * @param serverInfo the version of the server that is being used
 * @param buildTargetDescription small description on what a build target is
 */
final case class DoctorHeader(
    jdkInfo: Option[String],
    serverInfo: String,
    buildTargetDescription: String,
) {
  def toJson: Obj = {
    val base =
      ujson.Obj(
        "serverInfo" -> serverInfo,
        "buildTargetDescription" -> buildTargetDescription,
      )
    jdkInfo.foreach { jdki => base.update("jdkInfo", jdki) }
    base
  }
}

/**
 * @param buildTool if Metals detected multiple build tools, this specifies
 *        the one the user has chosen
 * @param buildServer the build server that is being used
 * @param importBuildStatus if the user has turned the import prompt off, this
 *        will include a message on how to get it back.
 */
final case class DoctorFolderHeader(
    buildTool: Option[String],
    buildServer: String,
    importBuildStatus: Option[String],
    isBuildServerResponsive: Option[Boolean],
) {
  def toJson: Obj = {
    val base =
      ujson.Obj(
        "buildServer" -> buildServer
      )

    buildTool.foreach { bt => base.update("buildTool", bt) }
    importBuildStatus.foreach { ibs => base.update("importBuildStatus", ibs) }
    isBuildServerResponsive.foreach { ibsr =>
      base.update("isBuildServerResponsive", ibsr)
    }
    base
  }
}

/**
 * Information about an error report.
 * @param name display name of the error
 * @param timestamp date and time timestamp of the report
 * @param uri error report file uri
 * @param buildTarget optional build target that error is associated with
 * @param shortSummary short error summary
 * @param errorReportType one of "metals", "metals-full", "bloop"
 */
final case class ErrorReportInfo(
    name: String,
    timestamp: Long,
    uri: String,
    buildTarget: Option[String],
    shortSummary: String,
    errorReportType: String,
) {
  def toJson: Obj = {
    val json = ujson.Obj(
      "name" -> name,
      "timestamp" -> timestamp,
      "uri" -> uri,
      "shortSummary" -> shortSummary,
      "errorReportType" -> errorReportType,
    )
    buildTarget.foreach(json("buildTarget") = _)
    json
  }
}
