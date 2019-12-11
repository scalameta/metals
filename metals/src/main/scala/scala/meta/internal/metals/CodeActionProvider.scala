package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final class CodeActionProvider(
    compilers: Compilers
) {

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    for {
      quickFixes <- Future
        .sequence(
          List(QuickFix.ImportMissingSymbol)
            .map(_.contribute(params, compilers, token))
        )
        .map(_.flatten)
    } yield quickFixes

  }

}
