package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.CodeActionBuilder
import scala.meta.internal.metals.logging
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken
import scala.meta.pc.CodeActionId

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
) extends CodeAction {

  override val kind: String = l.CodeActionKind.RefactorRewrite

  private case class ConvertToNamedLambdaParametersData(
      codeActionId: String,
      position: l.TextDocumentPositionParams,
  ) extends CodeActionResolveData

  override def resolveCodeAction(
      codeAction: l.CodeAction,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Option[Future[l.CodeAction]] = {
    parseData[ConvertToNamedLambdaParametersData](codeAction) match {
      case Some(data) =>
        Some {
          val uri = data.position.getTextDocument().getUri()
          for {
            edits <- compilers.codeAction(
              data.position,
              token,
              CodeActionId.ConvertToNamedLambdaParameters,
              None,
            )
            _ = logging.logErrorWhen(
              edits.isEmpty(),
              s"Could not convert lambda at position ${data.position} to named lambda",
            )
            workspaceEdit = new l.WorkspaceEdit(Map(uri -> edits).asJava)
            _ = codeAction.setEdit(workspaceEdit)
          } yield codeAction
        }
      case _ => None
    }
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
        val data =
          ConvertToNamedLambdaParametersData(
            codeActionId = CodeActionId.ConvertToNamedLambdaParameters,
            position = position,
          )
        val codeAction = CodeActionBuilder.build(
          title = ConvertToNamedLambdaParameters.title,
          kind = kind,
          data = Some(data.toJsonObject),
        )
        Future.successful(Seq(codeAction))
      }
      .filter(_ =>
        compilers
          .supportedCodeActions(path)
          .contains(CodeActionId.ConvertToNamedLambdaParameters)
      )
      .getOrElse(Future.successful(Nil))
  }

}

object ConvertToNamedLambdaParameters {
  def title: String = "Convert to named lambda parameters"
}
