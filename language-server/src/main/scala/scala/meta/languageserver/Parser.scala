package scala.meta.languageserver

import scala.meta.Dialect
import scala.meta.Source
import scala.meta.parsers.Parsed
import org.langmeta.inputs.Position
import org.langmeta.semanticdb.Document

// Small utility to parse inputs into scala.meta.Tree,
// this is missing in the API after semanticdb went language agnostics with langmeta.
object Parser {
  def parse(document: Document): Parsed[Source] =
    Dialect.standards.get(document.language) match {
      case Some(dialect) =>
        dialect(document.input).parse[Source]
      case None =>
        val err = s"Unknown dialect ${document.language}"
        Parsed.Error(Position.None, err, new IllegalArgumentException(err))
    }

}
