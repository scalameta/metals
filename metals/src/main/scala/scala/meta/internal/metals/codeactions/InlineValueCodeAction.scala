package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Pat
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.internal.metals.Compilers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class InlineValueCodeAction(
    trees: Trees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  type CommandData = ServerCommands.InlineValueParams

  override def command: Option[ActionCommand] =
    Some(ServerCommands.InlineValue)

  override def kind: String = l.CodeActionKind.RefactorInline

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val pathStr = params.getTextDocument.getUri
    val path = pathStr.toAbsolutePath
    val range = params.getRange()
    val action =
      for {
        termName <- getTermNameForPos(path, range)
        if (hasDefinitionInScope(termName))
        optThisDef = getDefinitionOfValue(path, range)
        if (optThisDef.map(isLocal(_)).getOrElse(true))
      } yield {
        val command =
          ServerCommands.InlineValue.toLsp(
            ServerCommands.InlineValueParams(
              params.getTextDocument(),
              termName.pos.toLsp,
              optThisDef.isDefined,
            )
          )
        CodeActionBuilder.build(
          title = InlineValueCodeAction.title(termName.value),
          kind = this.kind,
          command = Some(command),
        )
      }
    action.toSeq
  }

  private def isLocal(definition: Defn.Val): Boolean =
    definition.parent match {
      case Some(_: Term.Block) => true
      case _ => false
    }

  // Check if the definition is in the scope (in the same file)
  // NOTE: 1. import from e.g. `Object` in the file will return false
  //       2. can have FALSE POSITIVES but the condition is checked properly upon execution
  private def hasDefinitionInScope(
      nameTerm: Term.Name
  ): Boolean = {
    def isDefinition(t: Tree): Boolean = {
      t match {
        case v: Defn.Val =>
          v.pats.exists {
            case p: Pat.Var if (p.name.value == nameTerm.value) => true
            case _ => false
          }
        case _ => false
      }
    }

    def findSiblings(
        t: Tree
    ): List[Tree] =
      t match {
        case b: Term.Block => b.stats
        case t: Template => t.stats
        case _ => List()
      }

    def go(t: Tree): Boolean = {
      t.parent match {
        case Some(parent) if (isDefinition(parent)) => true
        case Some(parent) if (findSiblings(parent).exists(isDefinition(_))) =>
          true
        case Some(parent) => go(parent)
        case None => false
      }
    }

    go(nameTerm)
  }

  override def handleCommand(
      params: ServerCommands.InlineValueParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] =
    compilers
      .inlineEdits(
        params.textDocument.getUri,
        params.range,
        params.inlineAll,
        token,
      )
      .map { res =>
        res match {
          case Left(error) =>
            languageClient.showMessage(
              new l.MessageParams(
                l.MessageType.Info,
                s"Cannot inline, because $error",
              )
            )
          case Right(edits) =>
            languageClient.applyEdit(
              new l.ApplyWorkspaceEditParams(
                new l.WorkspaceEdit(
                  Map(params.textDocument.getUri -> edits.asJava).asJava
                )
              )
            )
        }
        ()
      }

  private def getTermNameForPos(
      path: AbsolutePath,
      range: l.Range,
  ): Option[Term.Name] = {
    def go(t: Tree): Option[Term.Name] =
      t.children.find(_.pos.encloses(range)) match {
        case Some(tn: Term.Name) => Some(tn)
        case Some(t) => go(t)
        case None => None
      }
    trees.get(path).flatMap(go(_))
  }

  private def getDefinitionOfValue(
      path: AbsolutePath,
      range: l.Range,
  ): Option[Defn.Val] = {
    def go(t: Tree): Option[Defn.Val] =
      t.children.find(_.pos.encloses(range)) match {
        case Some(v: Defn.Val) =>
          (v.pats
            .collect {
              case p: Pat.Var if (p.name.pos.encloses(range)) => Some(v)
            })
            .headOption
            .getOrElse(go(v))
        case Some(t) => go(t)
        case None => None
      }
    trees.get(path).flatMap(go(_))
  }
}

object InlineValueCodeAction {
  def title(name: String): String = {
    val optDots = if (name.length > 10) "..." else ""
    s"Inline `${name.take(10)}$optDots`"
  }
}
