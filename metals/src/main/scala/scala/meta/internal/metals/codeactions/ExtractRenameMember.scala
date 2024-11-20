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
import scala.meta.XtensionCollectionLikeUI
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.codeactions.CodeAction
import scala.meta.internal.metals.codeactions.ExtractRenameMember.CodeActionCommandNotFoundException
import scala.meta.internal.metals.codeactions.ExtractRenameMember.getMemberType
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token
import scala.meta.transversers.SimpleTraverser

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.{lsp4j => l}

class ExtractRenameMember(
    trees: Trees,
    languageClient: MetalsLanguageClient,
    buffers: Buffers,
)(implicit ec: ExecutionContext)
    extends CodeAction {

  override type CommandData = l.TextDocumentPositionParams

  override def command: Option[ActionCommand] =
    Some(ServerCommands.ExtractMemberDefinition)

  override def contribute(params: l.CodeActionParams, token: CancelToken)(
      implicit ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    val range = params.getRange

    trees.get(path) match {
      case Some(tree) =>
        val fileName = path.filename.replaceAll("\\.scala$", "")

        val definitions = membersDefinitions(tree)
        val sealedNames: List[String] = getSealedNames(tree)
        val defnAtCursor =
          definitions.find(_.member.name.pos.toLsp.overlapsWith(range))

        def canRenameDefn(defn: Member): Boolean = {
          val differentNames = defn.name.value != fileName
          val newFileUri = newPathFromClass(uri, defn)

          differentNames && !newFileUri.exists && defnAtCursor.exists(
            _.member.equals(defn)
          )
        }

        def canExtractDefn(defn: Member): Boolean = {
          val differentNames = defn.name.value != fileName
          val notExtendsSealedOrSealed = notSealed(defn, sealedNames)
          val companion = definitions.find(c => {
            !c.equals(defn) && c.member.name.value.equals(defn.name.value)
          })
          val companionNotSealed =
            companion.exists(endableMember =>
              notSealed(endableMember.member, sealedNames)
            ) || companion.isEmpty

          val newFileUri = newPathFromClass(uri, defn)

          differentNames && notExtendsSealedOrSealed && companionNotSealed && !newFileUri.exists
        }

        lazy val canExtractSingle = tree match {
          case Source((Pkg(_, stats)) :: Nil) =>
            stats.length > 1
          case Source(stats) =>
            stats.length > 1
          case _ => false
        }

        definitions match {
          case Nil => Nil
          case head :: Nil
              if canExtractSingle &&
                canExtractDefn(head.member) &&
                defnAtCursor.isDefined =>
            getMemberType(head.member).map { memberType =>
              val title =
                ExtractRenameMember.title(memberType, head.member.name.value)
              extractClassAction(uri, head.member, title)
            }.toList

          case head :: Nil
              if canRenameDefn(
                head.member
              ) =>
            Seq(renameFileAsMemberAction(uri, head.member))
          case _ =>
            val codeActionOpt = for {
              defn <- defnAtCursor
              if canExtractDefn(defn.member)
              memberType <- getMemberType(defn.member)
              title = ExtractRenameMember.title(
                memberType,
                defn.member.name.value,
              )
            } yield extractClassAction(uri, defn.member, title)

            codeActionOpt.toList
        }

      case _ => Nil
    }

  }

  private def membersDefinitions(tree: Tree): List[EndableMember] = {
    val nodes: ListBuffer[EndableMember] = ListBuffer()

    val traverser = new SimpleTraverser {
      override def apply(tree: Tree): Unit = tree match {
        case p: Pkg =>
          super.apply(p)
        case c: Defn.Class => nodes += EndableMember(c, None)
        case t: Defn.Trait => nodes += EndableMember(t, None)
        case o: Defn.Object => nodes += EndableMember(o, None)
        case e: Defn.Enum => nodes += EndableMember(e, None)
        case t: Defn.Type if t.mods.exists {
              case Mod.Opaque() => true
              case _ => false
            } =>
          nodes += EndableMember(t, None)
        case endMarker: Term.EndMarker =>
          nodes.lastOption match {
            case Some(last)
                if last.maybeEndMarker.isEmpty && endMarker.name.value == last.member.name.value =>
              nodes.remove(nodes.size - 1)
              nodes += EndableMember(last.member, Some(endMarker))
            case _ =>
          }
        case _: Source | _: Pkg.Body =>
          super.apply(tree)
        case _ =>
      }
    }
    traverser(tree)

    nodes.toList
  }

  private def findExtensions(
      tree: Tree
  ): List[EndableDefn[Defn.ExtensionGroup]] = {
    val nodes: ListBuffer[EndableDefn[Defn.ExtensionGroup]] = ListBuffer()

    val traverser = new SimpleTraverser {
      override def apply(tree: Tree): Unit = tree match {
        case p: Pkg => super.apply(p.body)
        case s: Source => super.apply(s)
        case e: Defn.ExtensionGroup =>
          nodes += EndableDefn[Defn.ExtensionGroup](e, None)
        case endMarker: Term.EndMarker if endMarker.name.value == "extension" =>
          nodes.lastOption match {
            case Some(last) if last.maybeEndMarker.isEmpty =>
              nodes.remove(nodes.size - 1)
              nodes += EndableDefn[Defn.ExtensionGroup](
                last.member,
                Some(endMarker),
              )
            case _ =>
          }
        case _ =>
      }
    }
    traverser(tree)

    nodes.toList
  }

  case class Comments(text: String, startPos: Int)

  type EndableMember = EndableDefn[Member]
  object EndableMember {
    def apply(
        member: Member,
        maybeEndMarker: Option[Term.EndMarker],
    ): EndableMember =
      EndableDefn(member, maybeEndMarker)
  }
  case class EndableDefn[T <: Tree](
      member: T,
      maybeEndMarker: Option[Term.EndMarker],
      commentsAbove: Option[Comments] = None,
  ) {
    def withComments(comments: Comments): EndableDefn[T] =
      this.copy(commentsAbove = Some(comments))
    def memberPos: Position =
      commentsAbove match {
        case None => member.pos
        case Some(comment) =>
          Position.Range(member.pos.input, comment.startPos, member.pos.end)
      }
    def endMarkerPos: Option[Position] = maybeEndMarker.map(_.pos)
  }

  private def isSealed(t: Tree): Boolean = t match {
    case node: Defn.Trait => node.mods.exists(_.isInstanceOf[Mod.Sealed])
    case node: Defn.Class => node.mods.exists(_.isInstanceOf[Mod.Sealed])
    case _ => false
  }

  private def getSealedNames(tree: Tree): List[String] = {
    def completeName(node: Member): String = {
      def completePreName(node: Tree): List[String] = {
        node.parent match {
          case Some(t) =>
            t match {
              case o: Defn.Object => o.name.value :: completePreName(o)
              case po: Pkg.Object => po.name.value :: completePreName(po)
              case _: Template | _: Template.Body => completePreName(t)
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
      sealedNames: List[String],
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
      endableMember: EndableMember,
      maybeCompanionEndableMember: Option[EndableMember],
      extensions: List[EndableDefn[Defn.ExtensionGroup]],
  ): (String, Int) = {
    // List of sequential packages or imports before the member definition
    val packages: ListBuffer[Pkg] = ListBuffer()
    val imports: ListBuffer[Import] = ListBuffer()

    // Using a custom traverser to avoid hitting inner classes by stopping the recursion on the chosen members
    object traverser extends SimpleTraverser {
      override def apply(tree: Tree): Unit = tree match {
        case p: Pkg if p.pos.toLsp.overlapsWith(range) =>
          packages += Pkg(ref = p.ref, stats = Nil)
          super.apply(p.body)
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

    def memberParts[T <: Tree](member: EndableDefn[T]) = {
      val endMarker =
        member.maybeEndMarker
          .map(endMarker => "\n" + endMarker.toString())
          .getOrElse("")
      member.commentsAbove.map(_.text).getOrElse("") +
        member.member.toString + endMarker
    }

    val definitionsParts = maybeCompanionEndableMember match {
      case None => List(memberParts(endableMember))
      case Some(companion)
          if (companion.memberPos.start < endableMember.memberPos.start) =>
        List(memberParts(companion), memberParts(endableMember))
      case Some(companion) =>
        List(memberParts(endableMember), memberParts(companion))
    }

    val structure =
      pkg.toList.mkString("\n") :: imports.mkString("\n") ::
        definitionsParts ++ extensions.map(memberParts)

    val preDefinitionLines = pkg.toList.length + imports.length
    val defnLine =
      if (preDefinitionLines == 0) 0
      else preDefinitionLines + 2 // empty line + defn line

    (
      structure
        .filter(_.nonEmpty)
        .mkString("\n\n"),
      defnLine,
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
      case _ => Nil
    }
  }

  private def renameFileAsMemberAction(
      uri: String,
      member: Member,
  ): l.CodeAction = {
    val className = member.name.value
    val newUri = newPathFromClass(uri, member).toURI.toString
    val fileName = uri.toAbsolutePath.filename

    val edits: List[Either[l.TextDocumentEdit, l.ResourceOperation]] = List(
      Right(new l.RenameFile(uri, newUri))
    )

    CodeActionBuilder.build(
      title = ExtractRenameMember.renameFileAsClassTitle(fileName, className),
      kind = l.CodeActionKind.Refactor,
      documentChanges = edits,
    )
  }

  private def extractClassAction(
      uri: String,
      member: Member,
      title: String,
  ): l.CodeAction = {
    val range = member.name.pos.toLsp

    val command =
      ServerCommands.ExtractMemberDefinition.toLsp(
        new l.TextDocumentPositionParams(
          new l.TextDocumentIdentifier(uri),
          range.getStart(),
        )
      )

    CodeActionBuilder.build(
      title,
      kind = l.CodeActionKind.RefactorExtract,
      command = Some(command),
    )
  }

  override def handleCommand(
      textDocumentParams: l.TextDocumentPositionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      (edits, goToLocation) <- calculate(textDocumentParams)
      _ <- languageClient.applyEdit(edits).asScala
    } yield {
      goToLocation.foreach { location =>
        languageClient.metalsExecuteClientCommand(
          ClientCommands.GotoLocation.toExecuteCommandParams(
            ClientCommands.WindowLocation(
              location.getUri(),
              location.getRange(),
            )
          )
        )
      }
    }

  }

  private def calculate(
      params: l.TextDocumentPositionParams
  ): Future[(l.ApplyWorkspaceEditParams, Option[Location])] = Future {
    val uri = params.getTextDocument().getUri()

    def isCompanion(
        member: Member
    )(candidateCompanion: EndableMember): Boolean = {
      val differentMemberWithSameName =
        !candidateCompanion.member.equals(member) &&
          candidateCompanion.member.name.value.equals(member.name.value)
      member match {
        case _: Defn.Object => differentMemberWithSameName
        case _ =>
          candidateCompanion.member
            .isInstanceOf[Defn.Object] && differentMemberWithSameName
      }
    }

    val pos = params.getPosition
    val range = new l.Range(pos, pos)
    val path = uri.toAbsolutePath

    def withComment(member: EndableMember) =
      findCommentsAbove(path, member.member) match {
        case Some(comments) => member.withComments(comments)
        case None => member
      }

    val opt = for {
      tree <- trees.get(path)
      text <- buffers.get(path)
      definitions = membersDefinitions(tree)
      memberDefn <- definitions
        .find(
          _.member.name.pos.toLsp.overlapsWith(range)
        )
        .map(withComment)
      companion = definitions
        .find(isCompanion(memberDefn.member))
        .map(withComment)
      extensions = findExtensions(tree).filter { e =>
        e.member.paramClauses.toList match {
          case Term.ParamClause(param :: Nil, _) :: Nil =>
            param.decltpe match {
              case Some(Type.Name(name)) =>
                name == memberDefn.member.name.value
              case _ => false
            }
          case _ => false
        }
      }
      (fileContent, defnLine) = newFileContent(
        tree,
        range,
        memberDefn,
        companion,
        extensions,
      )
      newFilePath = newPathFromClass(uri, memberDefn.member)
      if !newFilePath.exists

    } yield {
      val newFileUri = newFilePath.toURI.toString
      val edits = extractClassCommand(
        newFileUri,
        fileContent,
        memberDefn,
        companion,
        extensions,
        text,
      )
      val newFileMemberRange = new l.Range()
      val pos = new l.Position(defnLine, 0)
      newFileMemberRange.setStart(pos)
      newFileMemberRange.setEnd(pos)
      val workspaceEdit = new WorkspaceEdit(Map(uri -> edits.asJava).asJava)
      (
        new l.ApplyWorkspaceEditParams(workspaceEdit),
        Option(new Location(newFileUri, newFileMemberRange)),
      )
    }

    opt.getOrElse(
      throw CodeActionCommandNotFoundException(
        s"Could not execute command ${ServerCommands.ExtractMemberDefinition.id}"
      )
    )
  }

  private def newPathFromClass(uri: String, member: Member): AbsolutePath = {
    val src = uri.toAbsolutePath
    val classDefnName = member.name.value
    src.parent.resolve(s"$classDefnName.scala")
  }

  override def kind: String = l.CodeActionKind.RefactorExtract

  private def whiteChars = Set('\r', '\n', ' ', '\t')

  private def extractClassCommand(
      newUri: String,
      content: String,
      endableMember: EndableMember,
      maybeEndableMemberCompanion: Option[EndableMember],
      extensions: List[EndableDefn[Defn.ExtensionGroup]],
      fileText: String,
  ): List[l.TextEdit] = {
    val newPath = newUri.toAbsolutePath

    newPath.writeText(content)

    def removeEdit(pos: Position): l.TextEdit = new l.TextEdit(pos.toLsp, "")

    def removesPositionsForMember[T <: Tree](
        member: EndableDefn[T]
    ): List[Position] =
      member.memberPos :: member.endMarkerPos.toList

    val packageEdit = endableMember.member.parent
      .flatMap {
        case p: Pkg.Body
            if p.stats.forall(t =>
              t.isInstanceOf[Import] || t
                .equals(endableMember.member) || maybeEndableMemberCompanion
                .exists(_.member.equals(t))
            ) =>
          p.parent
        case _ => None
      }
      .map(tree => List(removeEdit(tree.pos)))

    // if there are only white chars between remove edits, we merge them
    def mergeEdits(
        edits: List[Position],
        acc: List[Position],
    ): List[Position] = {
      edits match {
        case edit1 :: edit2 :: rest =>
          def onlyWhiteCharsBetween =
            fileText.slice(edit1.end, edit2.start).forall(whiteChars)
          if (edit1.end >= edit2.start || onlyWhiteCharsBetween) {
            val merged = Position.Range(edit1.input, edit1.start, edit2.end)
            mergeEdits(merged :: rest, acc)
          } else {
            mergeEdits(edit2 :: rest, edit1 :: acc)
          }
        case edit :: Nil =>
          val followingWhites =
            fileText.splitAt(edit.end)._2.takeWhile(whiteChars).size
          Position.Range(
            edit.input,
            edit.start,
            edit.end + followingWhites,
          ) :: acc
        case Nil => acc
      }
    }

    def membersRemove: List[l.TextEdit] = {
      val positions =
        removesPositionsForMember(endableMember) ++
          maybeEndableMemberCompanion.toList.flatMap(
            removesPositionsForMember
          ) ++
          extensions
            .flatMap(removesPositionsForMember)
      mergeEdits(positions.sortBy(_.start), Nil).map(removeEdit)
    }

    packageEdit.getOrElse(membersRemove)
  }

  private def findCommentsAbove(path: AbsolutePath, member: Member) = {
    for {
      text <- buffers.get(path)
      (part, _) = text.splitAt(member.pos.start)
      tokenized <- part.safeTokenize(Trees.defaultTokenizerDialect).toOption
      collectComments = tokenized.tokens.reverse.takeWhile {
        case _: Token.EOF => true
        case _: Token.Comment => true
        case _: Token.Whitespace => true
        case _ => false
      }
      commentPos <- collectComments
        .findLast(_.isInstanceOf[Token.Comment])
        .map(_.pos)
    } yield Comments(
      part.splitAt(commentPos.start)._2,
      commentPos.start,
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
    case _: Defn.Type => "opaque type"
  }

  def title(memberType: String, name: String): String =
    s"Extract $memberType '$name' to file $name.scala"

  def renameFileAsClassTitle(fileName: String, memberName: String): String =
    s"Rename file $fileName as $memberName.scala"

  val extractDefCommandDataType = "extract-definition"
}
