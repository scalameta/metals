package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.MetalsEnrichments._
import scala.concurrent.Future
import scala.meta.pc.CancelToken
import scala.concurrent.ExecutionContext

final class CodeActionProvider(
    compilers: Compilers
) extends QuickFixes {

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    for {
      quickFixes <- QuickFix.fromDiagnostics(
        params.getContext().getDiagnostics().asScala.toSeq,
        params,
        compilers,
        token
      )
    } yield quickFixes

  }

}
