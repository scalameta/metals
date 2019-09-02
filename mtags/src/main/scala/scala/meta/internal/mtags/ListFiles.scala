package scala.meta.internal.mtags

import java.nio.file.Files
import scala.meta.io.AbsolutePath
import scala.collection.mutable
import java.util.stream.{Stream => JStream}
import java.nio.file.Path

object ListFiles {
  def apply(root: AbsolutePath): mutable.ArrayBuffer[AbsolutePath] = {
    val buf = mutable.ArrayBuffer.empty[AbsolutePath]
    foreach(root)(file => buf += file)
    buf
  }

  def exists(root: AbsolutePath)(fn: AbsolutePath => Boolean) =
    closing(root) {
      _.anyMatch(file => fn(AbsolutePath(file)))
    }

  def forall(root: AbsolutePath)(fn: AbsolutePath => Boolean) =
    closing(root) {
      _.allMatch(file => fn(AbsolutePath(file)))
    }

  def foreach(root: AbsolutePath)(fn: AbsolutePath => Unit) =
    closing(root) {
      _.forEach(file => fn(AbsolutePath(file)))
    }

  private def closing[T](
      root: AbsolutePath
  )(fn: JStream[Path] => T): T = {
    val ls = Files.list(root.toNIO)
    try {
      fn(ls)
    } finally {
      ls.close()
    }
  }
}
