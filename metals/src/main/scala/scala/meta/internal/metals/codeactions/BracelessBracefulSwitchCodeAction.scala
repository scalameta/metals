package scala.meta.internal.metals.codeactions

import org.eclipse.lsp4j.{CodeActionParams, TextEdit}
import org.eclipse.{lsp4j => l}

import scala.concurrent.{ExecutionContext, Future}
import scala.meta.inputs.Position
import scala.meta.internal.metals.Directories.log
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{Buffers, CodeAction}
import scala.meta.internal.parsing.Trees
import scala.meta.internal.trees.Origin.Parsed
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.{Case, Defn, Pkg, Template, Term, Tree}

class BracelessBracefulSwitchCodeAction(
    trees: Trees,
    buffers: Buffers
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

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
            createCodeActionForTemplateHolder(path, document, classDefn.templ)
          case objectDefn: Defn.Object =>
            createCodeActionForTemplateHolder(path, document, objectDefn.templ)
          case traitDefn: Defn.Trait =>
            createCodeActionForTemplateHolder(path, document, traitDefn.templ)
          case enumDefn: Defn.Enum =>
            createCodeActionForTemplateHolder(path, document, enumDefn.templ)
//          case valDefn: Defn.Val =>
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = valDefn,
//              braceableBranch = valDefn.rhs,
//              document = document,
//              bracelessStart = "",
//              bracelessEnd = ""
//            )
//          case varDefn: Defn.Var =>
//            varDefn.rhs
//              .flatMap(rhs =>
//                createCodeActionForBraceableTree(
//                  hasBraces = hasBraces,
//                  path = path,
//                  braceableTree = varDefn,
//                  braceableBranch = rhs,
//                  document = document,
//                  bracelessStart = "",
//                  bracelessEnd = ""
//                )
//              )
//          case defDefn: Defn.Def =>
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = defDefn,
//              braceableBranch = defDefn.body,
//              document = document,
//              bracelessStart = "",
//              bracelessEnd = ""
//            )
//          case termTry: Term.Try =>
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termTry,
//              braceableBranch = termTry.expr,
//              document = document,
//              bracelessStart = "",
//              bracelessEnd = ""
//            )
//            termTry.catchp.flatMap(catchp => // TODO
//              createCodeActionForBraceableTree(
//                hasBraces = hasBraces,
//                path = path,
//                braceableTree = termTry,
//                braceableBranch = catchp,
//                document = document,
//                bracelessStart = "",
//                bracelessEnd = ""
//              )
//            )

//            termTry.finallyp.flatMap(finallyP => // TODO
//              createCodeActionForBraceableTree(
//                hasBraces = hasBraces,
//                path = path,
//                braceableTree = termTry,
//                braceableBranch = finallyP,
//                document = document,
//                bracelessStart = "",
//                bracelessEnd = ""
//              )
//            )
          case caseTree: Case =>
            createCodeActionForCasesListWrapper(caseTree, path)
//          case termIf: Term.If =>
//            createCodeActionForBraceableTree( // TODO
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termIf,
//              braceableBranch = termIf.thenp,
//              document = document,
//              bracelessStart = "then",
//              bracelessEnd = ""
//            )
//            createCodeActionForBraceableTree( // TODO
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termIf,
//              braceableBranch = termIf.elsep,
//              document = document,
//              bracelessStart = "then",
//              bracelessEnd = ""
//            )
//          case termFor: Term.For => // TODO
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termFor,
//              braceableBranch = termFor.body,
//              document = document,
//              bracelessStart = "do",
//              bracelessEnd = ""
//            )
          case _: Term.Match => None
//          case termWhile: Term.While => // TODO
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termWhile,
//              braceableBranch = termWhile.body,
//              document = document,
//              bracelessStart = "do",
//              bracelessEnd = ""
//            )
          case _: Defn.GivenAlias => None
//          case template: Template =>
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = template,
//              braceableBranch = template,
//              document = document,
//              bracelessStart = ":",
//              bracelessEnd = ""
//            )
//          case termBlock: Term.Block =>
//            createCodeActionForBraceableTree(
//              hasBraces = hasBraces,
//              path = path,
//              braceableTree = termBlock,
//              braceableBranch = termBlock,
//              document = document,
//              bracelessStart = "",
//              bracelessEnd = ""
//            )
        }
      }
    result.flatten.toSeq
  }

  def createCodeActionForTemplateHolder(
      path: AbsolutePath,
      document: String,
      templ: Template
  ): Option[l.CodeAction] = {

    val maybeExpectedOpenBracePrecedingIndex =
      templ.inits.findLast(_ => true).map(init => init.pos.end)
    val expectedBraceStartPos = if (templ.inits.nonEmpty) {
      val lastInit = templ.inits.maxBy(init => init.pos.end)
      lastInit.pos.toLSP.getEnd
    } else templ.pos.toLSP.getStart
    if (
      hasBraces(
        templ,
        maybeExpectedOpenBracePrecedingIndex,
        None,
        Some(templ.pos.start),
        document
      )
    ) {
      if (templ.allowBracelessSyntax) {

        createCodeActionForGoingBraceless(
          path,
          expectedBraceStartPos = expectedBraceStartPos,
          expectedBraceEndPose = templ.pos.toLSP.getEnd,
          bracelessStart = ":",
          bracelessEnd = ""
        )
      } else None
    } else
      createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = expectedBraceStartPos,
        expectedBraceEndPose = templ.pos.toLSP.getEnd,
        bracelessStart = ":",
        bracelessEnd = "",
        braceableTree = templ,
        document = document
      )

  }

  private def hasBraces(
      braceContainingTree: Tree,
      maybeExpectedOpenBracePrecedingIndex: Option[Int],
      maybeExpectedOpenBraceSupercedingIndex: Option[Int],
      maybeExpectedBraceStartIndex: Option[Int],
      document: String
  ): Boolean =
    maybeExpectedOpenBracePrecedingIndex
      .map { expectedOpenBracePrecedingIndex =>
        braceContainingTree.tokens
          .find(token => token.start > expectedOpenBracePrecedingIndex)
          .map(token => token.text == "{")
          .getOrElse(false)
      }
      .orElse(
        maybeExpectedOpenBraceSupercedingIndex.map {
          expectedOpenBraceSupercedingIndex =>
            braceContainingTree.tokens
              .findLast(token => token.end > expectedOpenBraceSupercedingIndex)
              .map(token => token.text == "{")
              .getOrElse(false)
        }
      )
      .orElse(
        maybeExpectedBraceStartIndex.map(expectedOpenBraceIndex =>
          if (expectedOpenBraceIndex < document.size) {
            document(expectedOpenBraceIndex) == '{'
          } else false
        )
      )
      .getOrElse(false)

//  private def getBraceStartPos(braceContainingTree: Tree,
//                               maybeExpectedOpenBracePrecedingIndex: Option[Int],
//                               maybeExpectedOpenBraceSupercedingIndex: Option[Int],
//                               maybeExpectedBraceStartIndex: Option[Int],
//                               document: String): l.Position =
//    maybeExpectedOpenBracePrecedingIndex.map { expectedOpenBracePrecedingIndex =>
//      braceContainingTree.tokens
//        .find(token => token.start > expectedOpenBracePrecedingIndex)
//        .map(token => if (token.text == "{") token.pos.toLSP.getStart
//        else {
//          val result = token.pos.toLSP.getStart
//          result.setCharacter(result.getCharacter - 1)
//        }
//        ).getOrElse(new)
//    }.orElse(
//      maybeExpectedOpenBraceSupercedingIndex.map { expectedOpenBraceSupercedingIndex =>
//        braceContainingTree.tokens
//          .findLast(token => token.end > expectedOpenBraceSupercedingIndex)
//          .map(token => token.text == "{").getOrElse(false)
//      }
//    ).orElse(
//      maybeExpectedBraceStartIndex.map(expectedOpenBraceIndex =>
//        if (expectedOpenBraceIndex < document.size) {
//          document(expectedOpenBraceIndex) == '{'
//        } else false
//      )
//    ).getOrElse(false)

  def createCodeActionForGoingBraceful(
      path: AbsolutePath,
      braceableTree: Tree,
      document: String,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String
  ): Option[l.CodeAction] = {
    // TODO Important: autoIndentDocument()
    //  val braceableBranchLSPPos = braceableBranch.pos.toLSP
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = expectedBraceStartPos
    braceableBranchStartEnd.setCharacter(
      braceableBranchStart.getCharacter + bracelessStart.length
    )

    val braceableBranchEndStart = expectedBraceEndPose
    val braceableBranchEnd = expectedBraceEndPose
    braceableBranchStartEnd.setCharacter(
      braceableBranchStart.getCharacter + bracelessEnd.length
    )

    val startBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      "{"
    )
    val endBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
      s"""|
          |${getIndentationForPositionInDocument(braceableTree.pos, document)}}""".stripMargin
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

  def createCodeActionForGoingBraceless(
      path: AbsolutePath,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String
  ): Option[l.CodeAction] = {
    // TODO Important: autoIndentDocument()
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = expectedBraceStartPos
    //  braceableBranchStartEnd.setCharacter(braceableBranchStart.getCharacter + 1)
    //    braceableBranchStart.setCharacter(braceableBranchStart.getCharacter -1 )
    val braceableBranchEndStart = expectedBraceEndPose
    val braceableBranchEnd = expectedBraceEndPose
    //  braceableBranchStartEnd.setCharacter(braceableBranchStart.getCharacter + 1)
    //   braceableBranchEnd.setCharacter(braceableBranchEnd.getCharacter -1 )
    val startTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      bracelessStart
    )
    val endTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
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
  }

  def createCodeActionForCasesListWrapper(
      caseTree: Case,
      path: AbsolutePath
  ): Option[l.CodeAction] = {
    None
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
    case _: Template | _: Term.Block => true
    case _ => false
  }
}

object BracelessBracefulSwitchCodeAction {
  val goBraceFul = "Add braces"
  val goBraceless = "Remove braces"
}
