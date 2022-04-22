package scala.meta.internal.metals.codeactions

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Member
import scala.meta.Template
import scala.meta.Term
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
      nameAndType <- getNameAndTypeOfClassTraitOrEnumTree(tree)
      if !hasCompanionObject(tree, nameAndType)
      document <- buffers.get(path)
    } yield buildCreatingCompanionObjectCodeAction(
      path,
      tree,
      uri,
      getIndentationForPositionInDocument(tree.pos, document),
      nameAndType,
      hasBraces(tree, document),
      tree.canUseBracelessSyntax(document)
    )

    maybeCompanionObject.toSeq

  }

  private def getIndentationForPositionInDocument(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.startColumn, treePos.start)
      .takeWhile(_.isWhitespace)

  private def hasBraces(tree: Tree, document: String): Boolean = {
    tree.children
      .collectFirst { case template: Template =>
        if (template.pos.start < document.size)
          document(template.pos.start) == '{'
        else false
      }
      .getOrElse(false)
  }

  case class TreeNameAndType(name: String, treeType: String)

  private def getNameAndTypeOfClassTraitOrEnumTree(
      tree: Tree
  ): Option[TreeNameAndType] = {
    tree match {
      case classDefinition: Defn.Class =>
        Some(TreeNameAndType(classDefinition.name.value, "class"))
      case traitDefinition: Defn.Trait =>
        Some(TreeNameAndType(traitDefinition.name.value, "trait"))
      case enumDefinition: Defn.Enum =>
        Some(TreeNameAndType(enumDefinition.name.value, "enum"))
      case _ => None
    }
  }

  private def buildCreatingCompanionObjectCodeAction(
      path: AbsolutePath,
      tree: Tree,
      uri: String,
      indentationString: String,
      nameAndType: TreeNameAndType,
      hasBraces: Boolean,
      bracelessOK: Boolean
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(CreateCompanionObjectCodeAction.companionObjectCreation)
    codeAction.setKind(this.kind)
    val maybeEndMarkerEndPos =
      getEndMarker(tree, nameAndType).map(_.pos.toLSP.getEnd)
    val treePos = tree.pos
    val rangeStart =
      maybeEndMarkerEndPos.getOrElse(treePos.toLSP.getEnd)

    val range = new l.Range(rangeStart, rangeStart)

    val braceFulCompanion =
      s"""|
          |
          |${indentationString}object ${nameAndType.name} {
          |
          |${indentationString}}""".stripMargin

    val bracelessCompanion =
      s"""|
          |
          |${indentationString}object ${nameAndType.name}:
          |${indentationString}  ???""".stripMargin

    val companionObjectString =
      if (hasBraces || !bracelessOK) braceFulCompanion else bracelessCompanion

    val companionObjectTextEdit = new l.TextEdit(range, companionObjectString)

    val companionObjectStartPosition = new l.Position()
    companionObjectStartPosition.setLine(rangeStart.getLine + 3)

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
      nameAndType: TreeNameAndType
  ): Boolean =
    tree.parent
      .flatMap(_.children.collectFirst {
        case potentialCompanionObject: Defn.Object
            if (potentialCompanionObject.name.value == nameAndType.name) =>
          potentialCompanionObject
      })
      .isDefined

  case class MemberAndType(member: Member, memeberType: String)

  private def getEndMarker(
      tree: Tree,
      nameAndType: TreeNameAndType
  ): Option[Term.EndMarker] = {
    val nodes: ListBuffer[MemberAndType] = ListBuffer()
    tree.parent.flatMap {
      _.children.flatMap {
        {
          case c: Defn.Class =>
            nodes += MemberAndType(c, "class")
            None
          case t: Defn.Trait =>
            nodes += MemberAndType(t, "trait")
            None
          case o: Defn.Object =>
            nodes += MemberAndType(o, "object")
            None
          case e: Defn.Enum =>
            nodes += MemberAndType(e, "enum")
            None
          case endMarker: Term.EndMarker =>
            val last = nodes.remove(nodes.size - 1)
            if (
              last.member.name.value == endMarker.name.value && last.member.name.value == nameAndType.name && last.memeberType == nameAndType.treeType
            )
              Some(endMarker)
            else None
          case _ => None
        }
      }.headOption
    }
  }

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Defn.Class | _: Defn.Trait | _: Defn.Enum => true
    case _ => false
  }
}

object CreateCompanionObjectCodeAction {
  val companionObjectCreation = "Create companion object"
}
