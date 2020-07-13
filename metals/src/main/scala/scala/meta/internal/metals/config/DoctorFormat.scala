package scala.meta.internal.metals.config

object DoctorFormat {
  sealed trait DoctorFormat
  case object Json extends DoctorFormat
  case object Html extends DoctorFormat

  def fromString(value: String): Option[DoctorFormat] =
    value match {
      case "json" => Some(Json)
      case "html" => Some(Html)
      case _ => None
    }

}
