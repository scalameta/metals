package scala.meta.internal.metals.codelenses

import scala.annotation.nowarn

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

trait CodeLens {
  def isEnabled: Boolean

  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens] =
    Seq.empty

  def codeLenses(path: AbsolutePath): Seq[l.CodeLens] = Seq.empty
}
