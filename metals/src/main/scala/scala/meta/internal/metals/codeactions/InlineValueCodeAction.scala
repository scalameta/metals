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
import scala.meta.internal.parsing.JavaTrees
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class InlineValueCodeAction(
    trees: Trees,
    javaTrees: JavaTrees,
    compilers: Compilers,
    languageClient: MetalsLanguageClient,
) extends CodeAction {

  type CommandData = l.TextDocumentPositionParams

  override def command: Option[ActionCommand] =
    Some(ServerCommands.InlineValue)

  override def kind: String = l.CodeActionKind.RefactorInline

  override def isJava: Boolean = true

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    // `contribute` runs on every cursor movement, so it must stay cheap: it
    // only decides *whether* to offer the action (syntactically). The actual
    // edits are computed by the presentation compiler in `handleCommand`, when
    // the user invokes it.
    val path = params.getTextDocument.getUri.toAbsolutePath
    if (path.isJavaFilename) javaContribute(path, params)
    else scalaContribute(path, params)
  }

  private def scalaContribute(
      path: AbsolutePath,
      params: l.CodeActionParams,
  ): Seq[l.CodeAction] = {
    val range = params.getRange()
    val action =
      for {
        termName <- getTermNameForPos(path, range)
        (defn, isDefn) <- hasDefinitionInScope(termName)
        /* Either the value definition is local, which can be always inlined since it's scoped to the file
           or action was executed on a value reference so inlining just the reference will not break anything. */
        if (isLocal(defn) || !isDefn)
      } yield inlineCommand(params, InlineValueCodeAction.title(termName.value))
    action.toSeq
  }

  /**
   * A cheap syntactic check (via [[JavaTrees]]): is the cursor on a value that
   * could be inlined within the file? The presentation compiler does the real
   * work (and the precise feasibility check) lazily in `handleCommand`.
   */
  private def javaContribute(
      path: AbsolutePath,
      params: l.CodeActionParams,
  ): Seq[l.CodeAction] =
    if (isInlinableJavaValue(path, params.getRange().getStart()))
      Seq(inlineCommand(params, InlineValueCodeAction.genericTitle))
    else Nil

  private def inlineCommand(
      params: l.CodeActionParams,
      title: String,
  ): l.CodeAction =
    CodeActionBuilder.build(
      title = title,
      kind = this.kind,
      command = Some(
        ServerCommands.InlineValue.toLsp(
          new l.TextDocumentPositionParams(
            params.getTextDocument(),
            params.getRange().getStart(),
          )
        )
      ),
    )

  /**
   * Whether the cursor is on a value that is safe to inline within the file: a
   * local value inside any block (method body, initializer, lambda, ...), or a
   * `private` field of the enclosing class.
   */
  private def isInlinableJavaValue(
      path: AbsolutePath,
      position: l.Position,
  ): Boolean = {
    // Local values live inside a block; a private field is the only top-level
    // member safe to inline within the file (only locals can be private, so a
    // private variable at the cursor is necessarily a field).
    def insideBlock = javaTrees.isInsideBlock(path, position)
    def onPrivateField =
      javaTrees.findEnclosingJavaVariable(path, position).exists(_.isPrivate)
    insideBlock || onPrivateField
  }

  private def isLocal(definition: Tree): Boolean =
    definition.parent match {
      case Some(_: Term.Block) => true
      case Some(e) => isLocal(e)
      case _ => false
    }

  // Check if the definition is in the scope (in the same file)
  // NOTE: 1. import from e.g. `Object` in the file will return false
  //       2. can have FALSE POSITIVES but the condition is checked properly upon execution
  private def hasDefinitionInScope(
      nameTerm: Term.Name
  ): Option[(Defn.Val, Boolean)] = {
    def isDefinition(t: Tree): Option[(Defn.Val, Boolean)] = {
      t match {
        case v @ Defn.Val(_, List(Pat.Var(name)), _, _)
            if name.value == nameTerm.value =>
          Some(v, name.pos.encloses(nameTerm.pos))
        case _ => None
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

    def go(t: Tree): Option[(Defn.Val, Boolean)] = {
      t.parent match {
        case Some(parent) =>
          isDefinition(parent)
            .orElse {
              findSiblings(parent).collectFirst { s =>
                isDefinition(s) match {
                  case Some(res) => res
                }
              }
            }
            .orElse(go(parent))
        case None => None
      }
    }

    go(nameTerm)
  }

  override def handleCommand(
      params: l.TextDocumentPositionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] =
    compilers
      .inlineEdits(params, token)
      .map { edits =>
        if (!edits.isEmpty()) {
          languageClient.applyEdit(
            new l.ApplyWorkspaceEditParams(
              new l.WorkspaceEdit(
                Map(params.getTextDocument().getUri -> edits).asJava
              )
            )
          )
        }
        ()
      }
      .recover(
        // The presentation compiler reports an impossible inline (e.g. a
        // reassigned value) as a DisplayableException; show it instead of
        // inlining. Other failures propagate as usual.
        getOptDisplayableMessage.andThen { message =>
          languageClient.showMessage(
            new l.MessageParams(l.MessageType.Warning, message)
          )
        }
      )

  private def getTermNameForPos(
      path: AbsolutePath,
      range: l.Range,
  ): Option[Term.Name] =
    trees.findLastEnclosingAt[Term.Name](path, range.getStart())
}

object InlineValueCodeAction {
  def title(name: String): String = {
    val optDots = if (name.length > 10) "..." else ""
    s"Inline `${name.take(10)}$optDots`"
  }

  val genericTitle: String = "Inline value"
}
