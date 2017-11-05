package scala.meta.languageserver

import java.util

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
class Buffers private (contents: util.HashMap[String, String]) {
  def changed(uri: String, newContents: String): Unit =
    contents.put(uri, newContents)
  def read(uri: String): Option[String] = Option(contents.get(uri))
}
object Buffers {
  def apply(): Buffers = new Buffers(new util.HashMap())
}
