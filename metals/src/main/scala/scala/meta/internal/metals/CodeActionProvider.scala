package scala.meta.internal.metals

import org.eclipse.{lsp4j => l}
import scala.meta.pc.CancelToken
import scala.meta.internal.metals.codeactions._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.collection.JavaConverters._

final class CodeActionProvider(
    compilers: Compilers,
    interactiveSemanticdbs: InteractiveSemanticdbs,
    trees: Trees
) {

  val allActions: List[CodeAction] = List(
    new ImportMissingSymbol(compilers),
    new UseNamedArguments(
      compilers,
      interactiveSemanticdbs,
      trees
    )
  )

  def codeActions(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def isRequestedKind(action: CodeAction): Boolean =
      Option(params.getContext.getOnly) match {
        case Some(only) =>
          only.asScala.toSet.exists(requestedKind =>
            action.kind.startsWith(requestedKind)
          )
        case None => true
      }

    val actions = allActions.collect {
      case action if isRequestedKind(action) => action.contribute(params, token)
    }

    Future.sequence(actions).map(_.flatten)
  }

}
