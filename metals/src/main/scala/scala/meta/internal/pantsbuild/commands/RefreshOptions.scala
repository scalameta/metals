package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.generic.Settings
import metaconfig.{ConfDecoder, ConfEncoder}

case class RefreshOptions(
    @Hidden() @ExtraName("remainingArgs")
    projects: List[String] = Nil,
    @Hidden()
    update: Boolean = false,
    @Inline export: ExportOptions = ExportOptions.default,
    @Inline open: OpenOptions = OpenOptions.default,
    @Inline common: SharedOptions = SharedOptions()
) {
  def withCommon(common: SharedOptions): RefreshOptions =
    copy(common = common, open = open.copy(common = common))
}
object RefreshOptions {
  val default: RefreshOptions = RefreshOptions()
  implicit lazy val surface: generic.Surface[RefreshOptions] =
    generic.deriveSurface[RefreshOptions]
  implicit lazy val encoder: ConfEncoder[RefreshOptions] =
    generic.deriveEncoder[RefreshOptions]
  implicit lazy val decoder: ConfDecoder[RefreshOptions] =
    generic.deriveDecoder[RefreshOptions](default)
  implicit lazy val settings: Settings[RefreshOptions] =
    Settings[RefreshOptions]
}
