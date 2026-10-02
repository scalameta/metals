package scala.meta.internal.metals.config

import scala.meta.io.AbsolutePath

case class EclipseFormatConfig(
    eclipseFormatConfigPath: Option[AbsolutePath],
    eclipseFormatProfile: Option[String],
)

object EclipseFormatConfig {
  val default: EclipseFormatConfig =
    EclipseFormatConfig(None, Some("GoogleStyle"))
}
