package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}

case class LiveshareOptions(
    @Inline common: SharedOptions = SharedOptions()
)
object LiveshareOptions {
  val default: LiveshareOptions = LiveshareOptions()
  implicit lazy val surface: generic.Surface[LiveshareOptions] =
    generic.deriveSurface[LiveshareOptions]
  implicit lazy val encoder: ConfEncoder[LiveshareOptions] =
    generic.deriveEncoder[LiveshareOptions]
  implicit lazy val decoder: ConfDecoder[LiveshareOptions] =
    generic.deriveDecoder[LiveshareOptions](default)
  implicit lazy val settings: Settings[LiveshareOptions] =
    Settings[LiveshareOptions]
}
