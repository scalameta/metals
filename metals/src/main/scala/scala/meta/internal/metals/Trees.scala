package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.meta._
import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.parsers.Parsed

/**
 * Manages parsing of Scala source files into Scalameta syntax trees.
 *
 * - provides the latest good Scalameta tree for a given source file
 *   similar as `Buffers` provides the current text content.
 * - publishes diagnostics for syntax errors.
 */
final class Trees(buffers: Buffers, diagnostics: Diagnostics) {
  private val trees = TrieMap.empty[AbsolutePath, Tree]

  def get(path: AbsolutePath): Option[Tree] =
    trees.get(path).orElse {
      // Fallback to parse without caching result.
      parse(path).flatMap(_.toOption)
    }

  def didClose(path: AbsolutePath): Unit = {
    trees.remove(path)
    diagnostics.onNoSyntaxError(path)
  }

  def didChange(path: AbsolutePath): Unit = {
    parse(path) match {
      case Some(parsed) =>
        parsed match {
          case Parsed.Error(pos, message, _) =>
            diagnostics.onSyntaxError(path, pos, message)
          case Parsed.Success(tree) =>
            trees(path) = tree
            diagnostics.onNoSyntaxError(path)
        }
      case None =>
        () // Unknown extension, do nothing.
    }
  }

  private def parse(path: AbsolutePath): Option[Parsed[Source]] = {
    dialect(path).map { d =>
      val input = path.toInputFromBuffers(buffers)
      d(input).parse[Source]
    }
  }
  private def dialect(path: AbsolutePath): Option[Dialect] = {
    Option(PathIO.extension(path.toNIO)).collect {
      case "scala" => dialects.Scala212
      case "sbt" => dialects.Sbt
      case "sc" => dialects.Sbt
    }
  }
}
