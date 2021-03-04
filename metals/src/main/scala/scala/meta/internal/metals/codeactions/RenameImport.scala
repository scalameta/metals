package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Importee
import scala.meta.Name
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.trees.Origin.Parsed
import scala.meta.internal.mtags.Semanticdbs

class RenameImport(trees: Trees, semanticdbs: Semanticdbs) extends CodeAction {

  import RenameImport._
  override def kind: String = l.CodeActionKind.Refactor

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {

    def findLastEnclosingAtPos(tree: Tree, pos: Position): Option[Name] = {
      tree.children.find { child =>
        child.pos.start <= pos.start && pos.start <= child.pos.end
      } match {
        case None =>
          tree match {
            case name: Name => Some(name)
            case _ => None
          }
        case Some(value) => findLastEnclosingAtPos(value, pos)
      }
    }

    def renameImportCodeAction(name: Name): Option[l.CodeAction] =
      name.parent.flatMap {
        case nm @ Importee.Name(_) =>
          val nameRange = nm.pos.toLSP
          val uri = params.getTextDocument().getUri()
          val renameKeyword = name.origin match {
            case p: Parsed if p.dialect.allowAsForImportRename => "as"
            case _ => "=>"
          }

          // TODO make sure it does not exists via .startwith
          val randomName = s"Renamed${nm.name.value}"
          val renamedOccurrencesEdits =
            for {
              doc <- semanticdbs
                .textDocument(uri.toAbsolutePath)
                .documentIncludingStale
                .toIterable
              occ <- doc.occurrences
              // TODO make sure full imports are not renamed, probably check symbol
              range <- occ.range
              lspRange = range.toLSP
              if lspRange != nameRange
              // TODO inString should probably be a try, getting issues when tree doesn't parse
              if range.inString(doc.text) == nm.name.value
            } yield new TextEdit(lspRange, randomName)

          val text = s"{ $name $renameKeyword $randomName }"
          val goToRange = nameRange.copy()
          val newEndChar =
            text.length() - 3 + goToRange.getStart().getCharacter()
          goToRange
            .getEnd()
            .setCharacter(newEndChar)
          goToRange.setStart(goToRange.getEnd())

          val edit = new TextEdit(nameRange, text)
          val codeAction = new l.CodeAction()
          codeAction.setTitle(title)
          codeAction.setKind(l.CodeActionKind.RefactorRewrite)

          val location =
            new l.Location(params.getTextDocument().getUri(), goToRange)
          codeAction.setCommand(
            ServerCommands.RunRename.toLSP(
              List(location)
            )
          )
          codeAction.setEdit(
            new l.WorkspaceEdit(
              Map(uri -> (edit :: renamedOccurrencesEdits.toList).asJava).asJava
            )
          )

          Some(codeAction)
        case _ =>
          None
      }

    val path = params.getTextDocument().getUri().toAbsolutePath
    val action = for {
      tree <- trees.get(path)
      treePos = params.getRange().toMeta(tree.pos.input)
      name <- findLastEnclosingAtPos(tree, treePos)
      action <- renameImportCodeAction(name)
    } yield action

    action.toList
  }
}

object RenameImport {
  val title = "Rename import"
}
