package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.Case
import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

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
            createCodeActionForTemplateHolder(
              classDefn,
              path,
              document,
              classDefn.templ
            )
          case objectDefn: Defn.Object =>
            createCodeActionForTemplateHolder(
              objectDefn,
              path,
              document,
              objectDefn.templ
            )
          case traitDefn: Defn.Trait =>
            createCodeActionForTemplateHolder(
              traitDefn,
              path,
              document,
              traitDefn.templ
            )
          case enumDefn: Defn.Enum =>
            createCodeActionForTemplateHolder(
              enumDefn,
              path,
              document,
              enumDefn.templ
            )
          case valDefn: Defn.Val =>
            createCodeActionForAssignable(
              valDefn,
              path,
              document,
              valDefn.rhs
            )
          case varDefn: Defn.Var =>
            varDefn.rhs.flatMap {
              createCodeActionForAssignable(
                varDefn,
                path,
                document,
                _
              )
            }
          case defDefn: Defn.Def =>
            createCodeActionForAssignable(
              defDefn,
              path,
              document,
              defDefn.body
            )
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
          case termFor: Term.For =>
            createCodeActionForAssignable(
              termFor,
              path,
              document,
              termFor.body
            )
          case termForYield: Term.ForYield =>
            createCodeActionForAssignable(
              termForYield,
              path,
              document,
              termForYield.body
            )
          case _: Term.Match => None
          case termWhile: Term.While =>
            createCodeActionForAssignable(
              termWhile,
              path,
              document,
              termWhile.body
            )
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

  private def hasBraces(template: Template): Boolean = {
    util
      .Try(template.stats.maxBy(_.pos.end).pos.end)
      .getOrElse(-1) != template.pos.end && util
      .Try(template.tokens.maxBy(_.pos.end).text)
      .getOrElse("") == "}"
  }

  private def hasBraces(termBlock: Term.Block): Boolean = {
    util
      .Try(termBlock.stats.maxBy(_.pos.end).pos.end)
      .getOrElse(-1) != termBlock.pos.end && util
      .Try(termBlock.tokens.maxBy(_.pos.end).text)
      .getOrElse("") == "}"
  }

  /**
   * @param term the `rhs` of [[Defn.Var]] or [[Defn.Val]];
   *             or the `body` of [[Defn.Def]]
   * @return whether the `rhs` or `body` passed as the
   *         argument has braces
   */
  private def hasAssignedTermBraces(term: Term): Boolean = {
    term match {
      case termBlock: Term.Block => hasBraces(termBlock)
      case _ => false
    }
  }

  def createCodeActionForAssignable(
      assignee: Tree,
      path: AbsolutePath,
      document: String,
      assignedTerm: Term
  ): Option[l.CodeAction] = {
    val indentation =
      getIndentationForPositionInDocument(assignee.pos, document)

    if (
      hasAssignedTermBraces(assignedTerm)
      // util.Try(assignedTerm.tokens.maxBy(_.pos.end).text).getOrElse("") == "}"
    ) {
      for {
        bracePose <- util
          .Try(assignedTerm.tokens.minBy(_.pos.start))
          .toOption
          .map(_.pos.toLSP.getStart)
        if assignedTerm.allowBracelessSyntax
      } yield createCodeActionForGoingBraceless(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = assignedTerm.pos.toLSP.getEnd,
        bracelessStart = "",
        bracelessEnd = ""
      )

    } else { // does not have braces
      for {
        bracePose <- util
          .Try(assignedTerm.tokens.minBy(_.pos.start))
          .toOption
          .map(_.pos.toLSP.getStart)
      } yield createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = assignee.pos.toLSP.getEnd,
        bracelessStart = "",
        bracelessEnd = "",
        indentation = indentation,
        document = document
      )
    }
  }

  def createCodeActionForTemplateHolder(
      templateHolder: Tree,
      path: AbsolutePath,
      document: String,
      templ: Template
  ): Option[l.CodeAction] = {
    val indentation =
      getIndentationForPositionInDocument(templateHolder.pos, document)

    util.Try(templ.inits.maxBy(_.pos.end).pos.end).toOption
    val expectedBraceStartPos = util
      .Try {
        val lastInit = templ.inits.maxBy(init => init.pos.end)
        lastInit.pos.end
      }
      .getOrElse(templ.pos.start)
    pprint.log("templ.inits: \n" + templ.inits)
    if (
      hasBraces(templ)
      // templ.tokens.maxBy(_.pos.end).text == "}"
      //      templ.tokens
      //        .find(token => token.pos.start >= expectedBraceStartPos)
      //        .exists(token => token.text == "{")
      //      hasBraces(
      //        templ,
      //        maybeExpectedOpenBracePrecedingIndex,
      //        None,
      //        Some(templ.pos.start),
      //        document
      //      )
    ) {

      for {
        bracePose <- templ.tokens
          .find(token =>
            token.text == "{" && token.pos.start >= expectedBraceStartPos
          )
          .map(_.pos.toLSP.getStart)
        if templ.allowBracelessSyntax
      } yield createCodeActionForGoingBraceless(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = templ.pos.toLSP.getEnd,
        bracelessStart = ":",
        bracelessEnd = ""
      )

    } else { // does not have braces
      for {
        colonPose <- templ.tokens
          .find(token =>
            token.text == ":" && token.pos.start >= expectedBraceStartPos
          )
          .map(_.pos.toLSP.getStart)
      } yield createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = colonPose,
        expectedBraceEndPose = templ.pos.toLSP.getEnd,
        bracelessStart = ":",
        bracelessEnd = "",
        indentation = indentation,
        document = document
      )
    }

  }

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
      indentation: String,
      document: String,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String
  ): l.CodeAction = {
    // TODO Important: autoIndentDocument()
    //  val braceableBranchLSPPos = braceableBranch.pos.toLSP

    pprint.log("expectedBraceStartPos: " + expectedBraceStartPos)
    pprint.log("expectedBraceEndPose: " + expectedBraceEndPose)
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = new l.Position()
    braceableBranchStartEnd.setCharacter(
      expectedBraceStartPos.getCharacter + bracelessStart.length
    )
    braceableBranchStartEnd.setLine(expectedBraceStartPos.getLine)

    pprint.log("braceableBranchStartEnd: " + braceableBranchStartEnd)

    val startBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      "{"
    )
    pprint.log("startBraceTextEdit: \n" + startBraceTextEdit)

    val braceableBranchEndStart = expectedBraceEndPose
    val braceableBranchEnd = new l.Position()
    braceableBranchEnd.setCharacter(
      expectedBraceEndPose.getCharacter + bracelessEnd.length
    )
    braceableBranchEnd.setLine(expectedBraceEndPose.getLine)
    pprint.log("braceableBranchEnd: " + braceableBranchEnd)

    val endBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
      s"""|
          |$indentation}""".stripMargin
    )

    pprint.log("endBraceTextEdit: \n" + endBraceTextEdit)
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
    codeAction
  }

  def createCodeActionForGoingBraceless(
      path: AbsolutePath,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String
  ): l.CodeAction = {
    pprint.log("expectedBraceStartPos: " + expectedBraceStartPos)
    pprint.log("expectedBraceEndPose: " + expectedBraceEndPose)
    // TODO Important: autoIndentDocument()
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = new l.Position()
    braceableBranchStartEnd.setCharacter(expectedBraceStartPos.getCharacter + 1)
    braceableBranchStartEnd.setLine(expectedBraceStartPos.getLine)
    val startTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      bracelessStart
    )

    pprint.log("startTextEdit: \n" + startTextEdit)

    val braceableBranchEndStart = new l.Position()
    braceableBranchEndStart.setCharacter(expectedBraceEndPose.getCharacter - 1)
    braceableBranchEndStart.setLine(expectedBraceEndPose.getLine)
    val braceableBranchEnd = expectedBraceEndPose

    val endTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
      bracelessEnd
    )
    pprint.log("endTextEdit: \n" + endTextEdit)
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
    codeAction
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
