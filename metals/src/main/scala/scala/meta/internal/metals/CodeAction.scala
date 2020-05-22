package scala.meta.internal.metals

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

trait CodeAction {

  /**
   * This should be one of the String constants
   * listed in [[org.eclipse.lsp4j.CodeActionKind]]
   */
  def kind: String

  def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]

}
