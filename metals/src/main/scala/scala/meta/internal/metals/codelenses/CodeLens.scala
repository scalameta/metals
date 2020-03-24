package scala.meta.internal.metals.codelenses

import org.eclipse.{lsp4j => l}
import scala.meta.internal.implementation.TextDocumentWithPath

trait CodeLens {
  def isEnabled: Boolean
  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens]
}
