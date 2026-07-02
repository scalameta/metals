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
   * Whether the cursor is on an identifier, we can inline the value.
   * We might remove the definition is it's private or inside a block.
   */
  private def isInlinableJavaValue(
      path: AbsolutePath,
      position: l.Position,
  ): Boolean = {
    javaTrees
      .findEnclosingIdentifier(path, position)
      .orElse {
        javaTrees.findEnclosingJavaVariable(path, position).filter { v =>
          v.tree.getInitializer() != null
        }
      }
      .isDefined
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
