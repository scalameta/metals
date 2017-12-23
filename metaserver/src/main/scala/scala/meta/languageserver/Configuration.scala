package scala.meta.languageserver

import play.api.libs.json._

case class Configuration(
  scalafmtConfPath: String,
  scalafixConfPath: String,
  enableCompletions: Boolean,
)
object Configuration {
  implicit val format: OFormat[Configuration] = Json.format[Configuration]
}
