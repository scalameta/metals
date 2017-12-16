package scala.meta.languageserver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import com.typesafe.scalalogging.LazyLogging
import langserver.types.TextDocumentIdentifier
import langserver.types.VersionedTextDocumentIdentifier
import org.langmeta.io.AbsolutePath
import scala.meta.Source
import org.langmeta.inputs.Input

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
    contents: JMap[Uri, String],
    cwd: AbsolutePath
) extends LazyLogging {
  private def readFromDisk(path: AbsolutePath): String = {
    logger.info(s"Reading $path from disk")
    new String(Files.readAllBytes(path.toNIO), StandardCharsets.UTF_8)
  }
  def changed(input: Input.VirtualFile): Effects.UpdateBuffers = {
    contents.put(Uri(input.path), input.value)
    Effects.UpdateBuffers
  }
  def closed(uri: Uri): Unit = {
    contents.remove(uri)
    sources.remove(uri)
  }

  def read(td: TextDocumentIdentifier): String =
    read(Uri(td.uri))
  def read(td: VersionedTextDocumentIdentifier): String =
    read(Uri(td.uri))
  def read(path: AbsolutePath): String =
    read(Uri(path))
  def read(uri: Uri): String =
    Option(contents.get(uri))
      .getOrElse(readFromDisk(uri.toAbsolutePath))

  private val sources: JMap[Uri, Source] = new ConcurrentHashMap()
  // Tries to parse and record it or fallback to an old source if it existed
  def source(uri: Uri): Option[Source] =
    Parser
      .parse(read(uri))
      .toOption
      .map { tree =>
        sources.put(uri, tree)
        tree
      }
      .orElse(Option(sources.get(uri)))
}
object Buffers {
  def apply()(implicit cwd: AbsolutePath): Buffers =
    new Buffers(new ConcurrentHashMap(), cwd)
}
