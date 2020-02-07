package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.ConfCodec

case class ExportOptions(
    @Description(
      "The maximum number of source files to export. " +
        "This options exists to prevent the export from taking a long time to complete, " +
        "for example if exporting '::' in a large Pants build."
    )
    maxFileCount: Int = 5000,
    @Description(
      "Don't download *-sources.jar for 3rd party dependencies."
    )
    noSources: Boolean = false,
    @Hidden()
    mergeTargetsInSameDirectory: Boolean = false
)

object ExportOptions {
  val default: ExportOptions = ExportOptions()
  implicit lazy val surface: generic.Surface[ExportOptions] =
    generic.deriveSurface[ExportOptions]
  implicit lazy val codec: ConfCodec[ExportOptions] =
    generic.deriveCodec[ExportOptions](default)
}
