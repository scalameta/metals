package scala.meta.internal.pantsbuild

import java.nio.file.Path

import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath

import metaconfig.Conf.Str
import metaconfig.ConfDecoder
import metaconfig.ConfEncoder
import metaconfig.Configured

object Codecs {
  val workingDirectory = PathIO.workingDirectory
  implicit val pathDecoder: ConfDecoder[Path] =
    ConfDecoder.instance[Path] {
      case Str(path) =>
        Configured.ok(AbsolutePath(path)(workingDirectory).toNIO)
    }
  implicit val pathEncoder: ConfEncoder[Path] =
    ConfEncoder.instance[Path] { path => Str(path.toString()) }
}
