package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}

case class LinkOptions(
    @ExtraName("remainingArgs")
    @Hidden()
    projects: List[String] = Nil,
    @Inline common: SharedOptions = SharedOptions.default
)

object LinkOptions {
  val default: LinkOptions = LinkOptions()
  implicit lazy val surface: generic.Surface[LinkOptions] =
    generic.deriveSurface[LinkOptions]
  implicit lazy val encoder: ConfEncoder[LinkOptions] =
    generic.deriveEncoder[LinkOptions]
  implicit lazy val decoder: ConfDecoder[LinkOptions] =
    generic.deriveDecoder[LinkOptions](default)
  implicit lazy val settings: Settings[LinkOptions] = Settings[LinkOptions]
}
