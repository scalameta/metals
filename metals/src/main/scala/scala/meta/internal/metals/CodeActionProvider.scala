package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.meta.internal.mtags.Semanticdbs
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final class CodeActionProvider(
    compilers: Compilers,
    trees: Trees,
    buffers: Buffers,
    semanticdbs: Semanticdbs,
    symbolSearch: MetalsSymbolSearch
) {

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    for {
      quickFixes <- Future
        .sequence(
          List(QuickFix.ImportMissingSymbol)
            .map(_.contribute(params, compilers, token)) ++
            List(Refactoring.UseNamedArguments)
              .map(
                _.contribute(
                  params,
                  trees,
                  buffers,
                  semanticdbs,
                  symbolSearch,
                  token
                )
              )
        )
        .map(_.flatten)
    } yield quickFixes

  }

}
