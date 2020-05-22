package scala.meta.internal.pantsbuild.commands

import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.annotation._
import metaconfig.generic
import metaconfig.generic.Settings

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
