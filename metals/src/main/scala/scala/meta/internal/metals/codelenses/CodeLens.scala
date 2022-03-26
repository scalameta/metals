package scala.meta.internal.metals.codelenses

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}
import scala.annotation.nowarn

trait CodeLens {
  def isEnabled: Boolean

  @nowarn
  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens] =
    Seq.empty

  @nowarn
  def codeLenses(path: AbsolutePath): Seq[l.CodeLens] = Seq.empty
}
