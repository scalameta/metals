package scala.meta.languageserver

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import org.langmeta.lsp.TextDocumentIdentifier
import org.langmeta.lsp.VersionedTextDocumentIdentifier
import org.langmeta.lsp.VersionedTextDocumentIdentifier
import org.langmeta.inputs.Input
import org.langmeta.io.AbsolutePath

/**
 * Wrapper for a string representing a URI.
 *
 * The value is a String and not java.net.URI because
 * - URI has a lot of methods that return null and we don't use anyways
 * - URI supports any scheme while we are only interested in a couple schemes
 * - Both file:///path and file:/path are valid URIs while we only use
 *   file:///path in this project in order to support storing them as strings. For context, see https://github.com/scalameta/language-server/pull/127#issuecomment-351880150
 */
sealed abstract case class Uri(value: String) {
  // Runtime check because wrapping constructor in Option[Uri] is too cumbersome
  require(isJar || isFile, s"$value must start with file:///path or jar:")
  def isJar: Boolean =
    value.startsWith("jar:")
  def isFile: Boolean =
    value.startsWith("file:///") &&
      !value.startsWith("file:////")
  def toInput(buffers: Buffers): Input.VirtualFile =
    Input.VirtualFile(value, buffers.read(this))
  def toURI: URI = URI.create(value)
  def toPath: Path = Paths.get(toURI)
  def toAbsolutePath: AbsolutePath = AbsolutePath(toPath)
}

object Uri {
  def apply(uri: String): Uri = Uri(URI.create(uri))
  def file(path: String): Uri = {
    val slash = if (path.startsWith("/")) "" else "/"
    Uri(s"file:$slash${path.replace(' ', '-')}")
  }
  def apply(td: TextDocumentIdentifier): Uri = Uri(td.uri)
  def apply(td: VersionedTextDocumentIdentifier): Uri = Uri(td.uri)
  def apply(path: AbsolutePath): Uri = Uri(path.toURI)
  def apply(uri: URI): Uri =
    if (uri.getScheme == "file") {
      // nio.Path.toUri.toString produces file:/// while LSP expected file:/
      new Uri(s"file://${uri.getPath}") {}
    } else {
      new Uri(uri.toString) {}
    }
  def unapply(arg: String): Option[Uri] = Some(Uri(URI.create(arg)))
}
