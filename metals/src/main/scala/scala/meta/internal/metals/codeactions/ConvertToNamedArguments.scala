package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Term
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class ConvertToNamedArguments(trees: Trees, compilers: Compilers)
    extends CodeAction {
  override val kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = {

    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    trees
      .findLastEnclosingAt[Term.Apply](path, range.getStart())
      .map { apply =>
        {
          val numUnnamedArgs =
            apply.args.takeWhile(!_.isInstanceOf[Term.Assign]).length
          if (numUnnamedArgs == 0) Future.successful(Nil)
          else {
            val textDocumentPositionParams = new l.TextDocumentPositionParams(
              params.getTextDocument(),
              new l.Position(apply.pos.endLine, apply.pos.endColumn)
            )
            val edits = compilers.convertToNamedArguments(
              textDocumentPositionParams,
              numUnnamedArgs,
              token
            )
            edits.map(e => {
              val codeAction = new l.CodeAction("Convert to named arguments")
              codeAction.setEdit(
                new l.WorkspaceEdit(Map(path.toURI.toString -> e).asJava)
              )
              Seq(codeAction)
            })
          }
        }
      }
      .getOrElse(Future.successful(Nil))
  }
}
