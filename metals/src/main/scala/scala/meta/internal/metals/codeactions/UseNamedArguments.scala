package scala.meta.internal.metals.codeactions

import scala.concurrent.{Future, ExecutionContext}
import scala.meta._
import scala.meta.pc.CancelToken
import scala.meta.internal.metals._
import scala.meta.internal.metals.MetalsEnrichments._
import org.eclipse.{lsp4j => l}

class UseNamedArguments(
    compilers: Compilers,
    interactiveSemanticdbs: InteractiveSemanticdbs,
    trees: Trees
) extends CodeAction {

  override def kind: String = l.CodeActionKind.Refactor

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    def findMethodApplyOrCtorTreeUnderCursor(
        root: Tree,
        cursorPos: l.Range
    ): Option[Tree] =
      root
        .collect {
          case t: Term.Apply if t.pos.toLSP.encloses(cursorPos) => t
          case t: Init if t.pos.toLSP.encloses(cursorPos) => t
        }
        .sortBy(_.pos.start)
        .lastOption

    def findSymbolTree(tree: Tree): Option[Name] = tree match {
      case x @ Term.Name(_) => Some(x)
      case x @ Type.Name(_) => Some(x)
      case Term.Select(_, x) => Some(x)
      case Term.Apply(x, _) => findSymbolTree(x)
      case Term.ApplyType(x, _) => findSymbolTree(x)
      case Type.Apply(x, _) => findSymbolTree(x)
      case Init(x, _, _) => findSymbolTree(x)
      case _ => None
    }

    def buildEdits(
        tree: Tree,
        paramNames: List[String]
    ): List[l.TextEdit] = {
      val args = tree match {
        case Term.Apply(_, xs) => xs
        case Init(_, _, xss) => xss.flatten
        case _ => Nil
      }
      args.zip(paramNames).flatMap {
        case (Term.Assign(_, _), _) =>
          // already a named argument, no edit needed
          None
        case (term, paramName) =>
          val position = term.pos.toLSP.getStart
          val text = s"$paramName = "
          val edit = new l.TextEdit(new l.Range(position, position), text)
          Some(edit)
      }
    }

    val path = params.getTextDocument().getUri().toAbsolutePath

    val textDocPosParams = new l.TextDocumentPositionParams(
      params.getTextDocument,
      params.getRange.getStart
    )

    val parameterNames: Future[List[String]] = compilers
      .signatureHelp(textDocPosParams, token, interactiveSemanticdbs)
      .map { help =>
        if (help.getActiveSignature >= 0 && help.getActiveSignature < help.getSignatures.size) {
          val sig = help.getSignatures.get(help.getActiveSignature)
          val params = sig.getParameters.asScala
          val paramLabels = params.collect {
            case x if x.getLabel.isLeft => x.getLabel.getLeft
          }
          paramLabels.collect {
            // Type param labels are included in the list but do not include a colon, only the type name.
            // Method argument labels are in the form `foo: Int`
            case x if x.contains(": ") => x.split(": ").head
          }.toList
        } else Nil
      }

    parameterNames.map { paramNames =>
      (for {
        rootTree <- trees.get(path)
        methodApplyTree <- findMethodApplyOrCtorTreeUnderCursor(
          rootTree,
          params.getRange
        )
        symbolTree <- findSymbolTree(methodApplyTree)
      } yield {
        val codeEdits = buildEdits(
          methodApplyTree,
          paramNames
        )

        codeEdits match {
          case Nil => None // refactoring results in no changes to the code
          case edits =>
            val edit = new l.WorkspaceEdit()
            val uri = params.getTextDocument().getUri()
            val changes = Map(uri -> edits.asJava)

            val codeAction = new l.CodeAction()
            codeAction.setTitle(UseNamedArguments.title)
            codeAction.setKind(l.CodeActionKind.Refactor)

            edit.setChanges(changes.asJava)
            codeAction.setEdit(edit)
            Some(codeAction)
        }
      }).flatten.toSeq
    }

  }

}

object UseNamedArguments {

  val title = "Use named arguments"

}
