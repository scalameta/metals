package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

/**
 * Code action to convert a wildcard lambda to a lambda with named parameters
 * e.g.
 *
 * List(1, 2).map(<<_>> + 1) => List(1, 2).map(i => i + 1)
 */
class ConvertToNamedLambdaParameters(
    trees: Trees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  override val kind: String = l.CodeActionKind.RefactorRewrite

  override type CommandData =
    ServerCommands.ConvertToNamedLambdaParametersRequest

  override def command: Option[ActionCommand] = Some(
    ServerCommands.ConvertToNamedLambdaParameters
  )

  override def handleCommand(
      data: ServerCommands.ConvertToNamedLambdaParametersRequest,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val uri = data.position.getTextDocument().getUri()
    for {
      edits <- compilers.convertToNamedLambdaParameters(
        data.position,
        token,
      )
      _ = logging.logErrorWhen(
        edits.isEmpty(),
        s"Could not convert lambda at position ${data.position} to named lambda",
      )
      workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
      _ <- languageClient
        .applyEdit(new l.ApplyWorkspaceEditParams(workspaceEdit))
        .asScala
    } yield ()
  }

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val maybeLambda =
      trees.findLastEnclosingAt[Term.AnonymousFunction](path, range.getStart())
    maybeLambda
      .map { lambda =>
        val position = new l.TextDocumentPositionParams(
          params.getTextDocument(),
          new l.Position(lambda.pos.startLine, lambda.pos.startColumn),
        )
        val command =
          ServerCommands.ConvertToNamedLambdaParameters.toLsp(
            ServerCommands.ConvertToNamedLambdaParametersRequest(position)
          )
        val codeAction = CodeActionBuilder.build(
          title = ConvertToNamedLambdaParameters.title,
          kind = kind,
          command = Some(command),
        )
        Future.successful(Seq(codeAction))
      }
      .getOrElse(Future.successful(Nil))
  }

}

object ConvertToNamedLambdaParameters {
  def title: String = "Convert to named lambda parameters"
}
