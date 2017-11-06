package scala.meta.languageserver

import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import ScalametaEnrichments._

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
    contents: JMap[String, String],
    cwd: AbsolutePath
) {
  def changed(uri: String, newContents: String): Unit =
    contents.put(uri, newContents)
  def read(uri: String): Option[String] = Option(contents.get(uri))
  def read(path: RelativePath): Option[String] =
    Option(contents.get(cwd.resolve(path).toLanguageServerUri))
}
object Buffers {
  def apply()(implicit cwd: AbsolutePath): Buffers =
    new Buffers(new ConcurrentHashMap(), cwd)
}
