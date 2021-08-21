package scala.meta.internal.metals

import javax.annotation.Nullable

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier

case class HoverExtParams(
  textDocument: TextDocumentIdentifier,
  @Nullable position: Position = null,
  @Nullable range: Range = null
) {
  def getPosition: Position =
    if (position != null) position
    else range.getStart() 
}
