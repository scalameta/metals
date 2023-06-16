package scala.meta.internal.metals.codelenses

import scala.annotation.nowarn

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

trait CodeLens {
  def isEnabled: Boolean

  @nowarn(
    "msg=parameter textDocumentWithPath in method codeLenses is never used"
  )
  def codeLenses(textDocumentWithPath: TextDocumentWithPath): Seq[l.CodeLens] =
    Seq.empty

  @nowarn("msg=parameter path in method codeLenses is never used")
  def codeLenses(path: AbsolutePath): Seq[l.CodeLens] = Seq.empty
}
