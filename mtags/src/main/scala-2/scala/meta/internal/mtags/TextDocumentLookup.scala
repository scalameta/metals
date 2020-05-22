package scala.meta.internal.mtags

import scala.meta.AbsolutePath
import scala.meta.internal.{semanticdb => s}

sealed abstract class TextDocumentLookup {
  case class MissingSemanticdb(file: AbsolutePath)
      extends Exception(s"missing SemanticDB: $file")
  case class StaleSemanticdb(file: AbsolutePath)
      extends Exception(s"stale SemanticDB: $file")
  final def isNotFound: Boolean =
    this.isInstanceOf[TextDocumentLookup.NotFound]
  final def isSuccess: Boolean =
    this.isInstanceOf[TextDocumentLookup.Success]
  final def documentIncludingStale: Option[s.TextDocument] =
    this match {
      case TextDocumentLookup.Success(doc) => Some(doc)
      case TextDocumentLookup.Stale(_, _, doc) => Some(doc)
      case TextDocumentLookup.Aggregate(results) =>
        results.flatMap(_.documentIncludingStale).headOption
      case _ => None
    }
  final def toOption: Option[s.TextDocument] =
    this match {
      case TextDocumentLookup.Success(document) =>
        Some(document)
      case _ => None
    }
  final def get: s.TextDocument =
    getE match {
      case Left(e) => throw e
      case Right(v) => v
    }
  final def getE: Either[Throwable, s.TextDocument] =
    this match {
      case TextDocumentLookup.Success(document) =>
        Right(document)
      case TextDocumentLookup.NotFound(file) =>
        Left(MissingSemanticdb(file))
      case TextDocumentLookup.NoMatchingUri(file, _) =>
        Left(MissingSemanticdb(file))
      case TextDocumentLookup.Stale(file, _, _) =>
        Left(StaleSemanticdb(file))
      case TextDocumentLookup.Error(e, _) =>
        Left(e)
      case TextDocumentLookup.Aggregate(errors) =>
        val e = new Exception("Errors loading SemanticDB")
        errors.foreach { error =>
          error.getE.left.foreach(exception => e.addSuppressed(exception))
        }
        Left(e)
    }
}
object TextDocumentLookup {
  def fromOption(
      path: AbsolutePath,
      doc: Option[s.TextDocument]
  ): TextDocumentLookup =
    doc match {
      case Some(value) => Success(value)
      case None => NotFound(path)
    }
  case class Success(document: s.TextDocument) extends TextDocumentLookup
  case class Aggregate(errors: List[TextDocumentLookup])
      extends TextDocumentLookup
  case class Error(e: Throwable, path: AbsolutePath) extends TextDocumentLookup
  case class NotFound(file: AbsolutePath) extends TextDocumentLookup
  case class NoMatchingUri(file: AbsolutePath, documents: s.TextDocuments)
      extends TextDocumentLookup
  case class Stale(
      file: AbsolutePath,
      expectedMd5: String,
      document: s.TextDocument
  ) extends TextDocumentLookup
}
