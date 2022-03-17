package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Template
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Location
import org.eclipse.{lsp4j => l}

/**
 * It creates braceless or braceful companion objects for classes, traits, and enums
 * Then navigates to the position of the created object!
 *
 * @param trees
 */
class CreateCompanionObjectCodeAction(
    trees: Trees,
    buffers: Buffers
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val uri = params.getTextDocument().getUri()

    val path = uri.toAbsolutePath
    val range = params.getRange()
    val applyTree =
      if (range.getStart == range.getEnd)
        trees
          .findLastEnclosingAt[Tree](
            path,
            range.getStart(),
            applyWithSingleFunction
          )
      else
        None

    val maybeCompanionObject = for {
      tree <- applyTree
      name <- getNameOfClassTraitOrEnumTree(tree)
      if !hasCompanionObject(tree, name)
      document <- buffers.get(path)
    } yield buildCreatingCompanionObjectCodeAction(
      path,
      tree,
      uri,
      getIndentationForPositionInDocument(tree.pos, document),
      name,
      hasBraces(tree, document)
    )

    maybeCompanionObject.toSeq

  }

  private def getIndentationForPositionInDocument(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.endColumn + 1, treePos.start)
      .takeWhile(_.isWhitespace)

  private def hasBraces(tree: Tree, document: String): Boolean = {
    tree.children
      .collectFirst { case template: Template =>
        document(template.pos.start) == '{'
      }
      .getOrElse(false)
  }

  private def getNameOfClassTraitOrEnumTree(tree: Tree): Option[String] = {
    tree match {
      case classDefinition: Defn.Class => Some(classDefinition.name.value)
      case traitDefinition: Defn.Trait => Some(traitDefinition.name.value)
      case enumDefinition: Defn.Enum => Some(enumDefinition.name.value)
      case _ => None
    }
  }

  private def buildCreatingCompanionObjectCodeAction(
      path: AbsolutePath,
      tree: Tree,
      uri: String,
      indentationString: String,
      name: String,
      hasBraces: Boolean
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(CreateCompanionObjectCodeAction.companionObjectCreation)
    codeAction.setKind(this.kind)
    val treePos = tree.pos
    val rangeStart = treePos.toLSP.getEnd
    val rangeEnd = treePos.toLSP.getEnd

    rangeEnd.getCharacter
    rangeEnd.setLine(rangeEnd.getLine)
    val range = new l.Range(rangeStart, rangeEnd)

    val companionObjectString = if (hasBraces) {
      s"""|
          |
          |${indentationString}object $name {
          |
          |${indentationString}}""".stripMargin
    } else {
      s"""|
          |
          |${indentationString}object $name:
          |$indentationString   ???
          |""".stripMargin
    }

    val companionObjectTextEdit = new l.TextEdit(range, companionObjectString)

    val companionObjectStartPosition = new l.Position()
    companionObjectStartPosition.setLine(rangeEnd.getLine + 3)

    codeAction.setCommand(
      buildCommandForNavigatingToCompanionObject(
        uri,
        companionObjectStartPosition
      )
    )
    codeAction.setEdit(
      new l.WorkspaceEdit(
        Map(path.toURI.toString -> List(companionObjectTextEdit).asJava).asJava
      )
    )
    codeAction
  }

  private def buildCommandForNavigatingToCompanionObject(
      uri: String,
      companionObjectPosion: l.Position
  ): l.Command = {
    val cursorRange = new l.Range(companionObjectPosion, companionObjectPosion)
    ServerCommands.GotoPosition.toLSP(
      new Location(
        uri,
        cursorRange
      )
    )

  }

  private def hasCompanionObject(
      tree: Tree,
      name: String
  ): Boolean =
    tree.parent
      .flatMap(_.children.collectFirst {
        case potentialCompanionObject: Defn.Object
            if (potentialCompanionObject.name.value == name) =>
          potentialCompanionObject
      })
      .isDefined

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Defn.Class | _: Defn.Trait | _: Defn.Enum => true
    case _ => false
  }
}

object CreateCompanionObjectCodeAction {
  val companionObjectCreation = "Create companion object"
}
