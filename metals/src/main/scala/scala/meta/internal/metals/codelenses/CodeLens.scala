package scala.meta.internal.metals.codelenses

import scala.annotation.nowarn

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

trait CodeLens {
  def isEnabled: Boolean

  @nowarn("msg=parameter value textDocumentWithPath")
  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens] =
    Seq.empty

  @nowarn("msg=parameter value path")
  def codeLenses(path: AbsolutePath): Seq[l.CodeLens] = Seq.empty
}
