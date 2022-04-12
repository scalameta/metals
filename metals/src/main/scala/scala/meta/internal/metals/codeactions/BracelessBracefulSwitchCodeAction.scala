package scala.meta.internal.metals.codeactions

import org.eclipse.lsp4j.{CodeActionParams, TextEdit}
import org.eclipse.{lsp4j, lsp4j => l}

import scala.concurrent.{ExecutionContext, Future}
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{Buffers, CodeAction}
import scala.meta.internal.parsing.Trees
import scala.meta.internal.trees.Origin.Parsed
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.{Defn, Pkg, Template, Term, Tree}

class BracelessBracefulSwitchCodeAction(
    trees: Trees,
    buffers: Buffers
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  def createCodeActionForBraceableTree(
      hasBraces: (Tree, String) => Boolean,
      path: AbsolutePath,
      braceableTree: Tree,
      braceableBranch: Tree,
      bracelessStart: String,
      bracelessEnd: String,
      document: String
  ) = {
    if (hasBraces(braceableTree, document)) {
      if (braceableTree.allowBracelessSyntax) {
        // TODO `canUseBracelessSyntax` should be replaced by`canBeSyntacticallyBraceless`
        // TODO Important: autoIndentDocument()
        val braceableBranchLSPPos = braceableBranch.pos.toLSP
        val braceableBranchStart = braceableBranchLSPPos.getStart
        val braceableBranchEnd = braceableBranchLSPPos.getEnd
        val startTextEdit = new TextEdit(
          new l.Range(braceableBranchStart, braceableBranchStart),
          bracelessStart
        )
        val endTextEdit = new TextEdit(
          new l.Range(braceableBranchEnd, braceableBranchEnd),
          bracelessEnd
        )
        val codeAction = new l.CodeAction()
        codeAction.setTitle(BracelessBracefulSwitchCodeAction.goBraceless)
        codeAction.setKind(this.kind)
        codeAction.setEdit(
          new l.WorkspaceEdit(
            Map(
              path.toURI.toString -> List(startTextEdit, endTextEdit).asJava
            ).asJava
          )
        )
        Some(codeAction)
      } else {
        None
      }
    } else {
      // TODO Important: autoIndentDocument()
      val braceableBranchLSPPos = braceableBranch.pos.toLSP
      val braceableBranchStart = braceableBranchLSPPos.getStart
      val braceableBranchEnd = braceableBranchLSPPos.getEnd
      val startBraceTextEdit = new TextEdit(
        new l.Range(braceableBranchStart, braceableBranchStart),
        "{"
      )
      val endBraceTextEdit = new TextEdit(
        new l.Range(braceableBranchEnd, braceableBranchEnd),
        s"""|${document(braceableBranch.pos.end)}
            |${getIndentationForPositionInDocument(braceableBranch.pos, document)}}""".stripMargin
      )
      val codeAction = new l.CodeAction()
      codeAction.setTitle(BracelessBracefulSwitchCodeAction.goBraceFul)
      codeAction.setKind(this.kind)
      codeAction.setEdit(
        new l.WorkspaceEdit(
          Map(
            path.toURI.toString -> List(
              startBraceTextEdit,
              endBraceTextEdit
            ).asJava
          ).asJava
        )
      )
      Some(codeAction)
    }
  }

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()
    val maybeTree =
      if (range.getStart == range.getEnd)
        trees
          .findLastEnclosingAt[Tree](
            path,
            range.getStart(),
            applyWithSingleFunction
          )
      else None

    val result =
      for {
        tree <- maybeTree
        document <- buffers.get(path)
      } yield {
        tree match {
          case _: Pkg => None
          case classDefn: Defn.Class =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = classDefn,
              braceableBranch = classDefn.templ,
              document = document,
              bracelessStart = ":",
              bracelessEnd = ""
            )
          case objectDefn: Defn.Object =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = objectDefn,
              braceableBranch = objectDefn.templ,
              document = document,
              bracelessStart = ":",
              bracelessEnd = ""
            )
          case traitDefn: Defn.Trait =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = traitDefn,
              braceableBranch = traitDefn.templ,
              document = document,
              bracelessStart = ":",
              bracelessEnd = ""
            )
          case enumDefn: Defn.Enum =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = enumDefn,
              braceableBranch = enumDefn.templ,
              document = document,
              bracelessStart = ":",
              bracelessEnd = ""
            )
          case valDefn: Defn.Val =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = valDefn,
              braceableBranch = valDefn.rhs,
              document = document,
              bracelessStart = "",
              bracelessEnd = ""
            )
          case varDefn: Defn.Var =>
            varDefn.rhs
              .map(rhs =>
                createCodeActionForBraceableTree(
                  hasBraces = hasBraces,
                  path = path,
                  braceableTree = varDefn,
                  braceableBranch = rhs,
                  document = document,
                  bracelessStart = "",
                  bracelessEnd = ""
                )
              )
              .flatten
          case defDefn: Defn.Def =>
            createCodeActionForBraceableTree(
              hasBraces = hasBraces,
              path = path,
              braceableTree = defDefn,
              braceableBranch = defDefn.body,
              document = document,
              bracelessStart = "",
              bracelessEnd = ""
            )
          case _: Term.Try | _: Term.If | _: Term.For | _: Term.Match |
              _: Term.While =>
            None
          case _: Defn.GivenAlias => None
          case _: Template | _: Term.Block => None
        }
      }
    result.flatten.toSeq
  }

  private def hasBraces(tree: Tree, document: String): Boolean = {
    tree.children
      .collectFirst {
        case template: Template =>
          if (template.pos.start < document.size)
            document(template.pos.start) == '{'
          else false
        case rhs: Tree =>
          if (rhs.pos.start < document.size)
            document(rhs.pos.start) == '{'
          else false
        case body: Tree =>
          if (body.pos.start < document.size)
            document(body.pos.start) == '{'
          else false

      }
      .getOrElse(false)
  }

  private def getIndentationForPositionInDocument(
      treePos: Position,
      document: String
  ): String =
    document
      .substring(treePos.start - treePos.startColumn, treePos.start)
      .takeWhile(_.isWhitespace)

  private def applyWithSingleFunction: Tree => Boolean = {
    case _: Pkg | _: Defn.Class | _: Defn.Enum | _: Defn.Trait |
        _: Defn.Object =>
      true
    case _: Defn.GivenAlias | _: Defn.Val | _: Defn.Var | _: Defn.Def => true
    case _: Term.Try | _: Term.If | _: Term.For | _: Term.Match |
        _: Term.While =>
      true
    //   case _:Template | _: Term.Block => true
    case _ => false
  }
}

object BracelessBracefulSwitchCodeAction {
  val goBraceFul = "Add braces"
  val goBraceless = "Remove braces"
}
