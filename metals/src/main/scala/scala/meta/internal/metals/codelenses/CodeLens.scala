package scala.meta.internal.metals.codelenses

import scala.meta.internal.implementation.TextDocumentWithPath

import org.eclipse.{lsp4j => l}

trait CodeLens {
  def isEnabled: Boolean
  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens]
}
