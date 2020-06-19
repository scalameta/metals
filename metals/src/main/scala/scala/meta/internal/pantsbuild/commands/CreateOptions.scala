package scala.meta.internal.pantsbuild.commands

import scala.collection.immutable.Nil

import scala.meta.internal.pantsbuild.PantsConfiguration

import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.annotation._
import metaconfig.generic
import metaconfig.generic.Settings

case class CreateOptions(
    @ExtraName("remainingArgs")
    @Hidden()
    targets: List[String] = Nil,
    @Description(
      "The name of the generated project that appears in the IntelliJ projects view. " +
        "Should ideally be short, readable and easy to type. " +
        "Defaults to an auto-generated name based on the --targets option."
    )
    name: Option[String] = None,
    @Hidden() @Inline export: ExportOptions = ExportOptions.default,
    @Hidden() @Inline open: OpenOptions = OpenOptions.default,
    @Hidden() @Inline common: SharedOptions = SharedOptions.default
) {
  def actualName: String =
    name.getOrElse {
      PantsConfiguration.outputFilename(targets)
    }
}

object CreateOptions {
  val default: CreateOptions = CreateOptions()
  implicit lazy val surface: generic.Surface[CreateOptions] =
    generic.deriveSurface[CreateOptions]
  implicit lazy val encoder: ConfEncoder[CreateOptions] =
    generic.deriveEncoder[CreateOptions]
  implicit lazy val decoder: ConfDecoder[CreateOptions] =
    generic.deriveDecoder[CreateOptions](default)
  implicit lazy val settings: Settings[CreateOptions] = Settings[CreateOptions]
}
