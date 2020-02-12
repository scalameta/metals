package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import metaconfig.annotation._
import metaconfig.ConfCodec

case class InfoOptions(
    @Description("The name of the project to print out information about.")
    @ExtraName("remainingArgs")
    @Hidden()
    projects: List[String] = Nil,
    @Inline common: SharedOptions = SharedOptions()
)
object InfoOptions {
  val default: InfoOptions = InfoOptions()
  implicit lazy val surface: generic.Surface[InfoOptions] =
    generic.deriveSurface[InfoOptions]
  implicit lazy val codec: ConfCodec[InfoOptions] =
    generic.deriveCodec[InfoOptions](default)
}
