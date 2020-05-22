package scala.meta.internal.pantsbuild.commands

import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.annotation._
import metaconfig.generic
import metaconfig.generic.Settings

case class SwitchOptions(
    @ExtraName("remainingArgs")
    @Hidden()
    projects: List[String] = Nil,
    @Inline common: SharedOptions = SharedOptions.default
)

object SwitchOptions {
  val default: SwitchOptions = SwitchOptions()
  implicit lazy val surface: generic.Surface[SwitchOptions] =
    generic.deriveSurface[SwitchOptions]
  implicit lazy val encoder: ConfEncoder[SwitchOptions] =
    generic.deriveEncoder[SwitchOptions]
  implicit lazy val decoder: ConfDecoder[SwitchOptions] =
    generic.deriveDecoder[SwitchOptions](default)
  implicit lazy val settings: Settings[SwitchOptions] = Settings[SwitchOptions]
}
