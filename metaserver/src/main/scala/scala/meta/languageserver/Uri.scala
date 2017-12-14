package scala.meta.languageserver

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import scala.meta.Source
import org.langmeta.io.AbsolutePath

/**
 * Wrapper for a string representing an LSP-compliant URI.
 *
 * This is not a java.net.URI because
 * - URI a lot of methods that return null and we don't use anyways
 * - URI supports any scheme while we are only interested in a couple schemes
 * - Paths.get(URI).toString produces file:///path when LSP
 *   expects URIs to be formatted as file:/path
 */
case class Uri(value: String) {
  // Runtime check because wrapping constructor in Option[Uri] is too cumbersome
  require(isJar || isFile, s"$value must start with file: or jar:")
  def isJar: Boolean = value.startsWith("jar:")
  def isFile: Boolean = value.startsWith("file:")
  def toURI: URI = URI.create(value)
  def toPath: Path = Paths.get(toURI)
  def toAbsolutePath: AbsolutePath = AbsolutePath(toPath)
}

object Uri {
  def file(path: String): Uri = Uri(s"file:$path")
  def apply(path: AbsolutePath): Uri = Uri(s"file:$path")
  def apply(uri: URI): Uri =
    if (uri.getScheme == "file") {
      // nio.Path.toUri.toString produces file:/// while LSP expected file:/
      Uri(s"file:${uri.getPath}")
    } else {
      Uri(uri.toString)
    }
  def unapply(arg: String): Option[Uri] = Some(Uri(arg))
}
