package scala.meta.internal.pantsbuild.commands

import metaconfig._
import metaconfig.annotation._
import metaconfig.generic
import metaconfig.generic._

case class AmendOptions(
    @Hidden() @ExtraName("remainingArgs")
    projects: List[String] = Nil,
    @Description(
      "New comma-separated list of target specs " +
        "to use for this project. If not specified, opens " +
        "$EDITOR to manually enter the new list of target specs."
    )
    newTargets: Option[String] = None,
    @Inline open: OpenOptions = OpenOptions(),
    @Inline export: ExportOptions = ExportOptions(),
    @Inline common: SharedOptions = SharedOptions()
) {
  def targetsToAmend: Option[List[String]] =
    newTargets.map(_.split(",")).map(_.toList)
}

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
