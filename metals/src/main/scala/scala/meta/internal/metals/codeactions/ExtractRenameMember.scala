package scala.meta.internal.metals.codeactions

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Defn
import scala.meta.Import
import scala.meta.Member
import scala.meta.Mod
import scala.meta.Pkg
import scala.meta.Source
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.Type
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.codeactions.ExtractRenameMember.CodeActionCommandNotFoundException
import scala.meta.internal.metals.codeactions.ExtractRenameMember.getMemberType
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.transversers.SimpleTraverser

import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.{lsp4j => l}

class ExtractRenameMember(
    buffers: Buffers,
    trees: Trees
)(implicit ec: ExecutionContext)
    extends CodeAction {

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    val range = params.getRange

    trees.get(path) match {
      case Some(tree) =>
        val fileName = uri.toAbsolutePath.filename.replaceAll("\\.scala$", "")

        val definitions = membersDefinitions(tree)
        val sldNames = sealedNames(tree)
        val defnAtCursor =
          definitions.find(_.name.pos.toLSP.overlapsWith(range))

        def canRenameDefn(defn: Member): Boolean = {
          val differentNames = defn.name.value != fileName
          val newFileUri = newPathFromClass(uri, defn)

          differentNames && !newFileUri.exists && defnAtCursor.exists(
            _.equals(defn)
          )
        }

        def canExtractDefn(defn: Member): Boolean = {
          val differentNames = defn.name.value != fileName
          val notExtendsSealedOrSealed = notSealed(defn, sldNames)
          val companion = definitions.find(c => {
            !c.equals(defn) && c.name.value.equals(defn.name.value)
          })
          val companionNotSealed =
            companion.exists(notSealed(_, sldNames)) || companion.isEmpty

          val newFileUri = newPathFromClass(uri, defn)

          differentNames && notExtendsSealedOrSealed && companionNotSealed && !newFileUri.exists
        }

        definitions match {
          case Nil => Nil
          case head :: Nil if canRenameDefn(head) =>
            Seq(renameFileAsMemberAction(uri, head))
          case _ =>
            val codeActionOpt = for {
              defn <- defnAtCursor
              if canExtractDefn(defn)
              memberType <- getMemberType(defn)
              title = ExtractRenameMember.title(memberType, defn.name.value)
            } yield extractClassAction(uri, defn, title)

            codeActionOpt.toList
        }

      case _ => Nil
    }

  }

  private def membersDefinitions(tree: Tree): List[Member] = {
    val nodes: ListBuffer[Member] = ListBuffer()

    val traverser = new SimpleTraverser {
      override def apply(tree: Tree): Unit = tree match {
        case p: Pkg =>
          super.apply(p)
        case c: Defn.Class => nodes += c
        case t: Defn.Trait => nodes += t
        case o: Defn.Object => nodes += o
        case e: Defn.Enum => nodes += e
        case s: Source =>
          super.apply(s)
        case _ =>
      }
    }
    traverser(tree)

    nodes.toList
  }

  private def isSealed(t: Tree): Boolean = t match {
    case node: Defn.Trait => node.mods.exists(_.isInstanceOf[Mod.Sealed])
    case node: Defn.Class => node.mods.exists(_.isInstanceOf[Mod.Sealed])
    case _ => false
  }

  private def sealedNames(tree: Tree): List[String] = {
    def completeName(node: Member): String = {
      def completePreName(node: Tree): List[String] = {
        node.parent match {
          case Some(t) =>
            t match {
              case o: Defn.Object => o.name.value :: completePreName(o)
              case po: Pkg.Object => po.name.value :: completePreName(po)
              case tmpl: Template => completePreName(tmpl)
              case _: Source => Nil
              case _ => Nil
            }
          case None => Nil
        }
      }

      (node.name.value :: completePreName(node)).reverse.mkString(".")
    }

    tree.collect {
      case node: Defn.Trait if isSealed(node) => completeName(node)
      case node: Defn.Class if isSealed(node) => completeName(node)
    }
  }

  private def notSealed(
      member: Member,
      sealedNames: List[String]
  ): Boolean = {
    val memberExtendsSealed: Boolean =
      parents(member).exists(sealedNames.contains(_))

    !memberExtendsSealed && !isSealed(member)
  }

  private def names(t: Term): List[Term.Name] = {
    t match {
      case s: Term.Select => names(s.qual) :+ s.name
      case n: Term.Name => n :: Nil
    }
  }

  private def newFileContent(
      tree: Tree,
      range: l.Range,
      member: Member,
      companion: Option[Member]
  ): (String, Int) = {
    // List of sequential packages or imports before the member definition
    val packages: ListBuffer[Pkg] = ListBuffer()
    val imports: ListBuffer[Import] = ListBuffer()

    // Using a custom traverser to avoid hitting inner classes by stopping the recursion on the chosen members
    object traverser extends SimpleTraverser {
      override def apply(tree: Tree): Unit = tree match {
        case p: Pkg if p.pos.toLSP.overlapsWith(range) =>
          packages += Pkg(ref = p.ref, stats = Nil)
          super.apply(p)
        case i: Import =>
          imports += i
        case s: Source =>
          super.apply(s)
        case _ =>
      }
    }

    traverser(tree)

    def mergeNames(ns: List[Term.Name]): Option[Term.Ref] = {
      def merge(n1: Term.Ref, n2: Term.Name): Term.Select = n1 match {
        case s: Term.Select => Term.Select(qual = s, name = n2)
        case n: Term.Name => Term.Select(qual = n, name = n2)
      }

      ns match {
        case Nil => None
        case head :: Nil => Some(head)
        case head :: second :: xs =>
          Some(xs.foldLeft(merge(head, second))(merge))
      }
    }

    val termNames = packages
      .flatMap(p => names(p.ref))

    val mergedTermsOpt = mergeNames(termNames.toList)

    val pkg: Option[Pkg] = mergedTermsOpt.map(t => Pkg(ref = t, stats = Nil))

    val structure = pkg.toList.mkString("\n") ::
      imports.mkString("\n") ::
      member.toString ::
      companion.map(_.toString).getOrElse("") :: Nil

    val preDefinitionLines = pkg.toList.length + imports.length
    val defnLine =
      if (preDefinitionLines == 0) 0
      else preDefinitionLines + 2 // empty line + defn line

    (
      structure
        .filter(_.nonEmpty)
        .mkString("\n\n"),
      defnLine
    )
  }

  private def parents(member: Member): List[String] = {

    def namesFromTemplate(t: Template): List[String] = {
      t.inits.flatMap {
        _.tpe match {
          case Type.Name(value) => Some(value)
          case t: Type.Select =>
            Some(
              (names(t.qual) :+ t.name).mkString(".")
            )
          case _ => None
        }
      }
    }

    member match {
      case c: Defn.Class => namesFromTemplate(c.templ)
      case t: Defn.Trait => namesFromTemplate(t.templ)
      case o: Defn.Object => namesFromTemplate(o.templ)
      case e: Defn.Enum => namesFromTemplate(e.templ)
    }
  }

  private def renameFileAsMemberAction(
      uri: String,
      member: Member
  ): l.CodeAction = {
    val className = member.name.value
    val newUri = newPathFromClass(uri, member).toURI.toString
    val fileName = uri.toAbsolutePath.filename

    val edits: List[Either[l.TextDocumentEdit, l.ResourceOperation]] = List(
      Right(new l.RenameFile(uri, newUri))
    )

    val codeAction = new l.CodeAction()
    codeAction.setTitle(
      ExtractRenameMember.renameFileAsClassTitle(fileName, className)
    )
    codeAction.setKind(l.CodeActionKind.Refactor)
    codeAction.setEdit(
      new l.WorkspaceEdit(edits.map(_.asJava).asJava)
    )

    codeAction
  }

  private def extractClassAction(
      uri: String,
      member: Member,
      title: String
  ): l.CodeAction = {

    val range = member.name.pos.toLSP

    val codeAction = new l.CodeAction()
    codeAction.setTitle(title)
    codeAction.setKind(l.CodeActionKind.RefactorExtract)
    codeAction.setCommand(
      ServerCommands.ExtractMemberDefinition.toLSP(
        List(
          uri,
          range.getStart.getLine(): java.lang.Integer,
          range.getStart.getCharacter(): java.lang.Integer
        )
      )
    )

    codeAction
  }

  def executeCommand(
      data: ExtractMemberDefinitionData
  ): Future[CodeActionCommandResult] = Future {
    val uri = data.uri
    val params = data.params

    def isCompanion(member: Member)(candidateCompanion: Member): Boolean = {
      val differentMemberWithSameName = !candidateCompanion.equals(member) &&
        candidateCompanion.name.value.equals(member.name.value)
      member match {
        case _: Defn.Object => differentMemberWithSameName
        case _ =>
          candidateCompanion
            .isInstanceOf[Defn.Object] && differentMemberWithSameName
      }
    }

    val pos = params.getPosition
    val range = new l.Range(pos, pos)
    val path = uri.toAbsolutePath

    val opt = for {
      tree <- trees.get(path)
      definitions = membersDefinitions(tree)
      memberDefn <- definitions.find(_.name.pos.toLSP.overlapsWith(range))
      companion = definitions.find(isCompanion(memberDefn))
      (fileContent, defnLine) = newFileContent(
        tree,
        range,
        memberDefn,
        companion
      )
      newFilePath = newPathFromClass(uri, memberDefn)
      if !newFilePath.exists

    } yield {
      val newFileUri = newFilePath.toURI.toString
      val edits = extractClassCommand(
        newFileUri,
        fileContent,
        memberDefn,
        companion
      )
      val newFileMemberRange = new l.Range()
      val pos = new l.Position(defnLine, 0)
      newFileMemberRange.setStart(pos)
      newFileMemberRange.setEnd(pos)
      val workspaceEdit = new WorkspaceEdit(Map(uri -> edits.asJava).asJava)
      CodeActionCommandResult(
        new ApplyWorkspaceEditParams(workspaceEdit),
        Option(new Location(newFileUri, newFileMemberRange))
      )
    }

    opt.getOrElse(
      throw CodeActionCommandNotFoundException(
        s"Could not execute command ${data.actionType}"
      )
    )
  }

  private def newPathFromClass(uri: String, member: Member): AbsolutePath = {
    val src = uri.toAbsolutePath
    val classDefnName = member.name.value
    src.parent.resolve(s"$classDefnName.scala")
  }

  override def kind: String = l.CodeActionKind.RefactorExtract

  private def extractClassCommand(
      newUri: String,
      content: String,
      member: Member,
      companion: Option[Member]
  ): List[l.TextEdit] = {
    val newPath = newUri.toAbsolutePath

    newPath.writeText(content)

    def removeTreeEdits(t: Tree): List[l.TextEdit] =
      List(new l.TextEdit(t.pos.toLSP, ""))

    val packageEdit = member.parent
      .flatMap {
        case p: Pkg
            if p.stats.forall(t =>
              t.isInstanceOf[Import] || t.equals(member) || companion
                .exists(_.equals(t))
            ) =>
          Some(p)
        case _ => None
      }
      .map(removeTreeEdits)

    packageEdit.getOrElse(
      removeTreeEdits(member) ++ companion.map(removeTreeEdits).getOrElse(Nil)
    )

  }

}

object ExtractRenameMember {
  case class CodeActionCommandNotFoundException(s: String) extends Exception(s)

  def getMemberType(member: Member): Option[String] = Option(member).collect {
    case _: Defn.Class => "class"
    case _: Defn.Enum => "enum"
    case _: Defn.Trait => "trait"
    case _: Defn.Object => "object"
  }

  def title(memberType: String, name: String): String =
    s"Extract $memberType '$name' to file $name.scala"

  def renameFileAsClassTitle(fileName: String, memberName: String): String =
    s"Rename file $fileName as $memberName.scala"

  val extractDefCommandDataType = "extract-definition"
}
