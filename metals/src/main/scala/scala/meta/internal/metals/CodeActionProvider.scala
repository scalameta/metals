package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.codeactions._
import scala.meta.internal.mtags.Semanticdbs
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.collection.JavaConverters._

final class CodeActionProvider(
    compilers: Compilers,
    trees: Trees,
    buffers: Buffers,
    semanticdbs: Semanticdbs,
    symbolSearch: MetalsSymbolSearch,
    definitionProvider: DefinitionProvider
) {

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val allActions = List(
      ImportMissingSymbol,
      UseNamedArguments
    )

    val isRequestedKind: CodeAction => Boolean =
      Option(params.getContext.getOnly) match {
        case Some(only) =>
          action =>
            only.asScala.toSet.exists(requestedKind =>
              action.kind.startsWith(requestedKind)
            )
        case None =>
          _ => true
      }

    val actions = allActions
      .filter(isRequestedKind)
      .map(
        _.contribute(
          params,
          compilers,
          trees,
          buffers,
          semanticdbs,
          symbolSearch,
          definitionProvider,
          token
        )
      )
    Future.sequence(actions).map(_.flatten)
  }

}
