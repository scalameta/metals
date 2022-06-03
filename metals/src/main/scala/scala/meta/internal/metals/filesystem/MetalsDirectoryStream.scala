package scala.meta.internal.metals.filesystem

import java.nio.file.DirectoryStream
import java.nio.file.Path
import java.{util => ju}

import scala.collection.JavaConverters._

final case class MetalsDirectoryStream(
    paths: Iterable[Path],
    filter: DirectoryStream.Filter[_ >: Path]
) extends DirectoryStream[Path] {

  override def close(): Unit = {}

  override def iterator(): ju.Iterator[Path] =
    paths.filter(filter.accept).toIterator.asJava
}
