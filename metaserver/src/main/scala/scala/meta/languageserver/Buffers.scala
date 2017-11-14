package scala.meta.languageserver

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

/**
 * Utility to keep local state of file contents.
 *
 * If we use nio.Files.read directly then we don't pick up unsaved changes in
 * the editor buffer. Instead, on every file open/changed we update Buffers with
 * the file contents.
 *
 * It should be possible to avoid the need for this class, see
 * https://github.com/sourcegraph/language-server-protocol/blob/master/extension-files.md
 */
class Buffers private (
    contents: JMap[AbsolutePath, String],
    cwd: AbsolutePath
) extends LazyLogging {
  private def readFromDisk(path: AbsolutePath): String = {
    logger.info(s"Reading $path from disk")
    new String(Files.readAllBytes(path.toNIO), StandardCharsets.UTF_8)
  }
  def changed(path: AbsolutePath, newContents: String): Unit =
    contents.put(path, newContents)
  def read(uri: URI): String =
    read(AbsolutePath(uri.getPath))
  def read(path: RelativePath): String = // TODO(olafur) remove?
    read(cwd.resolve(path))
  def read(path: AbsolutePath): String =
    Option(contents.get(path)).getOrElse(readFromDisk(path))
}
object Buffers {
  def apply()(implicit cwd: AbsolutePath): Buffers =
    new Buffers(new ConcurrentHashMap(), cwd)
}
