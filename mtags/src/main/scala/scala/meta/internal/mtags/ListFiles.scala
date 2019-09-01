package scala.meta.internal.mtags

import java.nio.file.Files
import scala.meta.io.AbsolutePath
import scala.collection.mutable

object ListFiles {
  def apply(root: AbsolutePath): mutable.ArrayBuffer[AbsolutePath] = {
    val buf = mutable.ArrayBuffer.empty[AbsolutePath]
    foreach(root)(file => buf += file)
    buf
  }
  def foreach(root: AbsolutePath)(fn: AbsolutePath => Unit): Unit = {
    val ls = Files.list(root.toNIO)
    try {
      ls.forEach(file => fn(AbsolutePath(file)))
    } finally {
      ls.close()
    }
  }
}
