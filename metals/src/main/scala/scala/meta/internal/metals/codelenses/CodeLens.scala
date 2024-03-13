package scala.meta.internal.metals.codelenses

import scala.concurrent.Future

import scala.meta.internal.implementation.TextDocumentWithPath
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

trait CodeLens {
  def isEnabled: Boolean

  def codeLenses(
      textDocumentWithPath: TextDocumentWithPath
  ): Future[Seq[l.CodeLens]] =
    Future.successful(Seq.empty)

  def codeLenses(path: AbsolutePath): Future[Seq[l.CodeLens]] =
    Future.successful(Seq.empty)
}
