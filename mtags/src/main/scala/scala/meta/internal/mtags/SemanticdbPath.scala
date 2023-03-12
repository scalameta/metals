package scala.meta.internal.mtags

import java.nio.file.Path

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

final case class SemanticdbPath(val absolutePath: AbsolutePath) extends AnyVal {
  def semanticdbRoot: Option[Path] = absolutePath.toNIO.semanticdbRoot
  def toNIO: Path = absolutePath.toNIO
}
