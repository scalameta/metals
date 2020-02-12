package scala.meta.internal.pantsbuild.commands

import metaconfig.annotation._
import metaconfig._
import metaconfig.generic
import metaconfig.generic._

case class AmendOptions(
    @Hidden() @ExtraName("remainingArgs")
    projects: List[String] = Nil,
    @Inline open: OpenOptions = OpenOptions(),
    @Inline export: ExportOptions = ExportOptions(),
    @Inline common: SharedOptions = SharedOptions()
)

object AmendOptions {
  val default: AmendOptions = AmendOptions()
  implicit lazy val surface: generic.Surface[AmendOptions] =
    generic.deriveSurface[AmendOptions]
  implicit lazy val encoder: ConfEncoder[AmendOptions] =
    generic.deriveEncoder[AmendOptions]
  implicit lazy val decoder: ConfDecoder[AmendOptions] =
    generic.deriveDecoder[AmendOptions](default)
  implicit lazy val settings: Settings[AmendOptions] = Settings[AmendOptions]
}
