package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}

case class RemoveOptions(
    @Hidden() @ExtraName("remainingArgs")
    projects: List[String] = Nil,
    @Inline common: SharedOptions = SharedOptions()
)
object RemoveOptions {
  val default: RemoveOptions = RemoveOptions()
  implicit lazy val surface: generic.Surface[RemoveOptions] =
    generic.deriveSurface[RemoveOptions]
  implicit lazy val encoder: ConfEncoder[RemoveOptions] =
    generic.deriveEncoder[RemoveOptions]
  implicit lazy val decoder: ConfDecoder[RemoveOptions] =
    generic.deriveDecoder[RemoveOptions](default)
  implicit lazy val settings: Settings[RemoveOptions] =
    Settings[RemoveOptions]
}
