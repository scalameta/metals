package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.io.AbsolutePath

/**
 * Implements `TextDocuments` trait with a list of underlying implementations.
 */
final case class AggregateSemanticdbs(underlying: List[Semanticdbs])
    extends Semanticdbs {
  override def textDocument(path: AbsolutePath): TextDocumentLookup = {
    def loop(
        xs: List[Semanticdbs],
        errors: List[TextDocumentLookup]
    ): TextDocumentLookup =
      xs match {
        case Nil =>
          errors match {
            case Nil =>
              TextDocumentLookup.NotFound(path)
            case head :: Nil =>
              head
            case errors =>
              TextDocumentLookup.Aggregate(errors)
          }
        case head :: tail =>
          val result = head.textDocument(path)
          if (result.isSuccess) result
          else if (result.isNotFound) loop(tail, errors)
          else loop(tail, result :: errors)
      }
    try {
      loop(underlying, Nil)
    } catch {
      case NonFatal(e) =>
        scribe.error(s"text document: ${path.toURI}", e)
        TextDocumentLookup.Error(e, path)
    }
  }
}
