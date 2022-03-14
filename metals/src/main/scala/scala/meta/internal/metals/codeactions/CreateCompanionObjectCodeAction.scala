package scala.meta.internal.metals.codeactions

import java.util.Scanner
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
 * look at the
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

    applyTree.collect { case tree: Tree =>
      val name = getName(tree)

      findCompanionObject(tree, name) match {
        case Some(comanionObject) =>
          buildShowingCompanionObjectCodeAction(comanionObject, uri)
        case None =>
          val document = buffers.get(path).getOrElse("")
          val indentationString =
            getIndentationForPositionInDocument(tree.pos, document)

          buildCreatingCompanionObjectCodeAction(
            path,
            tree,
            uri,
            indentationString,
            name,
            hasBraces(tree, document)
          )
      }
    }.toSeq

  }

  private def getIndentationForPositionInDocument(
      treePos: Position,
      document: String
  ) = {
    val scanner = new Scanner(document)

    (0 to treePos.startLine)
      .map(_ => scanner.nextLine())
      .last
      .takeWhile(_.isWhitespace)
  }

  private def hasBraces(tree: Tree, document: String): Boolean = {
    tree.children
      .collectFirst { case template: Template =>
        document(template.pos.start) == '{'
      }
      .getOrElse(false)
    //  document(tree.pos.end-1) == '}'
  }

  private def getName(tree: Tree): String = {
    tree match {
      case classDefinition: Defn.Class => classDefinition.name.value
      case traitDefinition: Defn.Trait => traitDefinition.name.value
      case enumDefinition: Defn.Enum => enumDefinition.name.value
      case _ => ""
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

  private def buildShowingCompanionObjectCodeAction(
      companionObject: Defn.Object,
      uri: String
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(CreateCompanionObjectCodeAction.companionObjectInfo)
    codeAction.setCommand(
      buildCommandForNavigatingToCompanionObject(
        uri,
        companionObject.pos.toLSP.getStart
      )
    )

    codeAction.setKind(this.kind)
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

  private def findCompanionObject(
      tree: Tree,
      name: String
  ): Option[Defn.Object] =
    tree.parent.flatMap(_.children.collectFirst {
      case potentialCompanionObject: Defn.Object
          if (potentialCompanionObject.name.value == name) =>
        potentialCompanionObject
    })

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Defn.Class =>
      true
    case _: Defn.Trait =>
      true
    case _: Defn.Enum =>
      true
    case _ =>
      false
  }
}

object CreateCompanionObjectCodeAction {
  val companionObjectCreation = "Create companion object"
  val companionObjectInfo = "Show companion object"
}
