package scala.meta.languageserver

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import com.typesafe.scalalogging.LazyLogging
import langserver.types.TextDocumentIdentifier
import langserver.types.VersionedTextDocumentIdentifier
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import scala.meta.Source

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
  def changed(
      path: AbsolutePath,
      newContents: String
  ): Effects.UpdateBuffers = {
    contents.put(path, newContents)
    Effects.UpdateBuffers
  }
  def closed(path: AbsolutePath): Unit = {
    contents.remove(path)
    sources.remove(path)
  }

  def read(td: TextDocumentIdentifier): String =
    read(URI.create(td.uri))
  def read(td: VersionedTextDocumentIdentifier): String =
    read(URI.create(td.uri))
  def read(uri: URI): String =
    read(AbsolutePath(uri.getPath))
  def read(path: RelativePath): String = // TODO(olafur) remove?
    read(cwd.resolve(path))
  def read(path: AbsolutePath): String =
    Option(contents.get(path)).getOrElse(readFromDisk(path))

  private val sources: JMap[AbsolutePath, Source] = new ConcurrentHashMap()
  // Tries to parse and record it or fallback to an old source if it existed
  def source(path: AbsolutePath): Option[Source] =
    Parser
      .parse(read(path))
      .toOption
      .map { tree =>
        sources.put(path, tree)
        tree
      }
      .orElse(Option(sources.get(path)))
}
object Buffers {
  def apply()(implicit cwd: AbsolutePath): Buffers =
    new Buffers(new ConcurrentHashMap(), cwd)
}
