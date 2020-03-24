package scala.meta.internal.implementation

import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

case class TextDocumentWithPath(
    textDocument: TextDocument,
    filePath: AbsolutePath
)
