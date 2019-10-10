package scala.meta.internal.metals

import scala.collection.concurrent.TrieMap
import scala.meta._
import scala.meta.internal.io.PathIO
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.parsers.Parsed
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.nio.file.Paths

/**
 * Manages parsing of Scala source files into Scalameta syntax trees.
 *
 * - provides the latest good Scalameta tree for a given source file
 *   similar as `Buffers` provides the current text content.
 * - publishes diagnostics for syntax errors.
 */
final class Trees {

  private val trees = TrieMap.empty[String, Tree]

  def get(fileName: String, code: String): Option[Tree] =
    trees.get(fileName).orElse {
      // Fallback to parse without caching result.
      parse(fileName, code).flatMap(_.toOption)
    }

  def didClose(fileName: String): Unit = {
    trees.remove(fileName)
  }

  def didChange(fileName: String, text: String): List[Diagnostic] = {
    parse(fileName, text) match {
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
            trees(fileName) = tree
            List()
        }
      case None =>
        () // Unknown extension, do nothing.
        List()
    }
  }

  private def parse(
      fileName: String,
      text: String
  ): Option[Parsed[Source]] = {
    dialect(fileName).map { d =>
      val input = Input.VirtualFile(fileName, text)
      d(input).parse[Source]
    }
  }
  private def dialect(fileName: String): Option[Dialect] = {
    // TODO check if this works
    Option(PathIO.extension(Paths.get(fileName))).collect {
      case "scala" => dialects.Scala
      case "sbt" => dialects.Sbt
      case "sc" => dialects.Sbt
    }
  }
}
