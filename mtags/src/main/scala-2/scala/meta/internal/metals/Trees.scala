package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths

import scala.collection.concurrent.TrieMap

import scala.meta._
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.parsers.Parsed

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity

/**
 * Manages parsing of Scala source files into Scalameta syntax trees.
 *
 * - provides the latest good Scalameta tree for a given source file
 *   similar as `Buffers` provides the current text content.
 * - produces diagnostics for syntax errors.
 */
final class Trees {

  private val trees = TrieMap.empty[URI, Tree]

  def get(fileUri: URI, code: String): Option[Tree] =
    trees.get(fileUri).orElse {
      // Fallback to parse without caching result.
      parse(fileUri, code).flatMap(_.toOption)
    }

  def didClose(fileUri: URI): Unit = {
    trees.remove(fileUri)
  }

  def didChange(fileUri: URI, text: String): List[Diagnostic] = {
    parse(fileUri, text) match {
      case Some(parsed) =>
        parsed match {
          case Parsed.Error(pos, message, _) =>
            List(
              new Diagnostic(
                pos.toLSP,
                message,
                DiagnosticSeverity.Error,
                "scalameta"
              )
            )
          case Parsed.Success(tree) =>
            trees(fileUri) = tree
            List()
        }
      case None =>
        () // Unknown extension, do nothing.
        List()
    }
  }

  private def parse(
      fileUri: URI,
      text: String
  ): Option[Parsed[Source]] = {
    dialect(fileUri).map { d =>
      val input = Input.VirtualFile(Paths.get(fileUri).toString(), text)
      d(input).parse[Source]
    }
  }
  private def dialect(fileUri: URI): Option[Dialect] = {
    Option(PathIO.extension(Paths.get(fileUri))).collect {
      case "scala" => dialects.Scala
      case "sbt" => dialects.Sbt
      case "sc" =>
        dialects.Scala213
          .copy(allowToplevelTerms = true, toplevelSeparator = "")
    }
  }
}

object Trees {

  implicit val defaultDialect: Dialect = scala.meta.dialects.Scala213

}
