package scala.meta.internal.metals

final case class DoctorTargetInfo(
    name: String,
    scalaVersion: String,
    definitionStatus: String,
    completionsStatus: String,
    referencesStatus: String,
    recommenedFix: String
)
