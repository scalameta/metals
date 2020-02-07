package scala.meta.internal.pantsbuild.commands

import metaconfig.generic
import java.nio.file.Path
import scala.meta.internal.pantsbuild.Codecs._
import metaconfig.annotation._
import metaconfig.generic.Settings
import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import metaconfig.{ConfDecoder, ConfEncoder}
import scala.meta.internal.io.PathIO

case class SharedOptions(
    @Description("The root directory of the Pants build.")
    workspace: Path = PathIO.workingDirectory.toNIO
) {
  val pants: AbsolutePath = AbsolutePath(workspace.resolve("pants"))
  def bloopDirectory: Path = workspace.resolve(".bloop")
  val home: AbsolutePath = AbsolutePath {
    Option(System.getenv("FASTPASS_HOME")) match {
      case Some(value) => Paths.get(value)
      case None => workspace.resolveSibling("intellij-bsp")
    }
  }
}

object SharedOptions {
  val default: SharedOptions = SharedOptions()
  implicit lazy val surface: generic.Surface[SharedOptions] =
    generic.deriveSurface[SharedOptions]
  implicit lazy val encoder: ConfEncoder[SharedOptions] =
    generic.deriveEncoder[SharedOptions]
  implicit lazy val decoder: ConfDecoder[SharedOptions] =
    generic.deriveDecoder[SharedOptions](default)
  implicit lazy val settings: Settings[SharedOptions] = Settings[SharedOptions]
}
