package tests

import java.nio.charset.StandardCharsets
import scala.meta.inputs.Input
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scala.meta.internal.mtags.MtagsEnrichments._

case class InputFile(
    file: AbsolutePath,
    sourceDirectory: AbsolutePath,
    semanticdbRelativePath: RelativePath
) {
  def sourceDirectoryRelativePath: RelativePath =
    file.toRelative(sourceDirectory)
  def input: Input.VirtualFile = file.toInput
  def isScala: Boolean = PathIO.extension(file.toNIO) == "scala"
  def expectPath(name: String): AbsolutePath =
    AbsolutePath(BuildInfo.testResourceDirectory)
      .resolve(name)
      .resolve(sourceDirectoryRelativePath)
  def slurpExpected(name: String): Option[String] = {
    if (expectPath(name).isFile) {
      Some(FileIO.slurp(expectPath(name), StandardCharsets.UTF_8))
    } else {
      None
    }
  }
}
