package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
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

    def quickfixes =
      List(QuickFix.ImportMissingSymbol)
        .map(_.contribute(params, compilers, token))

    def refactorings =
      List(Refactoring.UseNamedArguments)
        .map(
          _.contribute(
            params,
            trees,
            buffers,
            semanticdbs,
            symbolSearch,
            definitionProvider,
            token
          )
        )

    // when we have more refactorings, we should separate them according to the LSP "code action kind",
    // e.g. refactoring inline, refactoring extraction, refactoring rewrite

    val requestedKinds = Option(params.getContext.getOnly) match {
      case Some(only) => only.asScala.toList
      case None =>
        // if client did not specify, return all actions by default
        List(l.CodeActionKind.QuickFix, l.CodeActionKind.Refactor)
    }

    val actions = requestedKinds flatMap {
      case l.CodeActionKind.QuickFix => quickfixes
      case l.CodeActionKind.Refactor => refactorings
      case _ => Nil
    }

    Future.sequence(actions).map(_.flatten)
  }

}
