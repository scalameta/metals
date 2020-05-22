package scala.meta.internal.pantsbuild.commands

import java.nio.file.Path

import metaconfig.ConfCodec
import metaconfig.annotation._
import metaconfig.generic

case class ExportOptions(
    @Description(
      "Disable downloading of -sources.jar for 3rd party dependencies."
    )
    disableSources: Boolean = false,
    @Description(
      "Enable downloading of sources for 3rdparty dependencies. This flag has no impact unless " +
        "downloading of sources has been disabled for this project via the --disable-sources flag."
    )
    enableSources: Boolean = false,
    @Description(
      "The path to the coursier binary." +
        "If unspecified, coursier will be downloaded automatically."
    )
    coursierBinary: Option[Path] = None,
    @Hidden()
    @Description("When enabled, Fastpass will not exit the Bloop server.")
    noBloopExit: Boolean = false,
    @Hidden()
    mergeTargetsInSameDirectory: Boolean = false
) {
  def canBloopExit: Boolean = !noBloopExit
}

object ExportOptions {
  val default: ExportOptions = ExportOptions()
  implicit lazy val surface: generic.Surface[ExportOptions] =
    generic.deriveSurface[ExportOptions]
  implicit lazy val codec: ConfCodec[ExportOptions] =
    generic.deriveCodec[ExportOptions](default)
}
