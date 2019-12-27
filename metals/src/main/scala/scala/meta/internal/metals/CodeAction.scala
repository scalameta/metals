package scala.meta.internal.metals

import scala.concurrent.{Future, ExecutionContext}
import org.eclipse.{lsp4j => l}
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.pc.CancelToken

trait CodeAction {

  /**
   * This should be one of the String constants
   * listed in [[org.eclipse.lsp4j.CodeActionKind]]
   */
  def kind: String

  def contribute(
      params: l.CodeActionParams,
      compilers: Compilers,
      trees: Trees,
      buffers: Buffers,
      semanticdbs: Semanticdbs,
      symbolSearch: MetalsSymbolSearch,
      definitionProvider: DefinitionProvider,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]]

}
