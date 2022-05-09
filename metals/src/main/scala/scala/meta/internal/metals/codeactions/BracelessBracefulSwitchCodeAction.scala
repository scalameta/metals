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
          case _: Pkg => None.toSeq
          case classDefn: Defn.Class =>
            createCodeActionForTemplateHolder(
              classDefn,
              path,
              document,
              classDefn.templ,
              "class definition"
            ).toSeq
          case objectDefn: Defn.Object =>
            createCodeActionForTemplateHolder(
              objectDefn,
              path,
              document,
              objectDefn.templ,
              "object definition"
            ).toSeq
          case traitDefn: Defn.Trait =>
            createCodeActionForTemplateHolder(
              traitDefn,
              path,
              document,
              traitDefn.templ,
              "trait definition"
            ).toSeq
          case enumDefn: Defn.Enum =>
            createCodeActionForTemplateHolder(
              enumDefn,
              path,
              document,
              enumDefn.templ,
              "enum definition"
            ).toSeq
          case valDefn: Defn.Val =>
            createCodeActionForPotentialBlockHolder(
              valDefn,
              path,
              document,
              valDefn.rhs,
              "val definition"
            ).toSeq
          case varDefn: Defn.Var =>
            varDefn.rhs.flatMap {
              createCodeActionForPotentialBlockHolder(
                varDefn,
                path,
                document,
                _,
                "var definition"
              )
            }.toSeq
          case defDefn: Defn.Def =>
            createCodeActionForPotentialBlockHolder(
              defDefn,
              path,
              document,
              defDefn.body,
              "def definition"
            ).toSeq
          case termTry: Term.Try =>
            Seq(
              createCodeActionForPotentialBlockHolder(
                termTry,
                path,
                document,
                termTry.expr,
                "try expression"
              ),
              createCodeActionForCatchP(
                termTry,
                path,
                document,
                "catch expression"
              ),
              termTry.finallyp.flatMap(finallyp =>
                createCodeActionForPotentialBlockHolder(
                  termTry,
                  path,
                  document,
                  finallyp,
                  "finally expression"
                )
              )
            ).flatten
          case caseTree: Case =>
            createCodeActionForCasesListWrapper(caseTree, path).toSeq
          case termIf: Term.If =>
            Seq(
              createCodeActionForPotentialBlockHolder(
                termIf,
                path,
                document,
                termIf.thenp,
                "then expression"
              ),
              createCodeActionForPotentialBlockHolder(
                termIf,
                path,
                document,
                termIf.elsep,
                "else expression"
              )
            ).flatten
          case termFor: Term.For =>
            createCodeActionForPotentialBlockHolder(
              termFor,
              path,
              document,
              termFor.body,
              "for expression"
            ).toSeq
          case termForYield: Term.ForYield =>
            createCodeActionForPotentialBlockHolder(
              termForYield,
              path,
              document,
              termForYield.body,
              "yield expression"
            ).toSeq
          case _: Term.Match => None.toSeq
          case termWhile: Term.While =>
            createCodeActionForPotentialBlockHolder(
              termWhile,
              path,
              document,
              termWhile.body,
              "while expression"
            ).toSeq
          case _: Defn.GivenAlias => None.toSeq
          //          case template: Template =>
          //            createCodeActionForTemplateHolder(
          //              template,
          //              path,
          //              document,
          //              template,
          //              "template"
          //            ).toSeq
          case termBlock: Term.Block if !termBlock.parent.exists(_ match {
                case _: Term.Apply => true
                case _ => false
              }) =>
            createCodeActionForPotentialBlockHolder(
              termBlock.parent.getOrElse(termBlock),
              path,
              document,
              termBlock,
              "block"
            ).toSeq
          case termMatch: Term.Match =>
            createCodeActionForTermMatch(
              termMatch,
              path,
              document,
              "match cases"
            )
        }
      }
    result.toSeq.flatten
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
   * @param blockEmbraceable the `rhs` of [[Defn.Var]] or [[Defn.Val]];
   *                         or the `body` of [[Defn.Def]]
   * @return whether the `rhs` or `body` passed as the
   *         argument has braces
   */
  private def isBlockEmbraceableBraced(blockEmbraceable: Term): Boolean = {
    blockEmbraceable match {
      case termBlock: Term.Block => hasBraces(termBlock)
      case _ => false
    }
  }

  private def createCodeActionForCatchP(
      termTry: Term.Try,
      path: AbsolutePath,
      document: String,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val maybeCatchToken = util
      .Try(
        termTry.tokens.tokens
          .filter(token =>
            token.pos.end > termTry.expr.pos.end && token.text == "catch"
          )
          .minBy(_.pos.end)
      )
      .toOption
    pprint.log("maybeCatchToken is " + maybeCatchToken)
    val maybeFirstCase =
      util.Try(termTry.catchp.minBy(_.pos.start)).toOption
    val potentialOpenBraceToken = maybeCatchToken
      .flatMap(catchToken =>
        util
          .Try(
            termTry.tokens.tokens
              .find(token =>
                token.pos.end > catchToken.pos.end &&
                  token.text == "{" &&
                  maybeFirstCase.forall(token.pos.start < _.pos.start)
              )
          )
          .toOption
          .flatten
      )

    pprint.log("potentialOpenBraceToken is " + potentialOpenBraceToken)
    val maybeLastCase =
      util.Try(termTry.catchp.maxBy(_.pos.end)).toOption

    pprint.log("maybeLastCase is " + maybeLastCase)
    util
      .Try(termTry.finallyp.map(_.tokens.tokens.minBy(_.pos.start)))
      .toOption
      .flatten
    val maybeFinallyToken = termTry.tokens.find { finallyToken =>
      maybeLastCase.forall(_.pos.end < finallyToken.pos.end) &&
      termTry.finallyp.forall(_.pos.start > finallyToken.pos.start) &&
      finallyToken.text == "finally"
    }
    val maybeFinallyStartPos = maybeFinallyToken.map(_.pos.start)
    pprint.log("maybeFinallyToken is " + maybeFinallyToken)
    val potentialEndBraceToken = maybeLastCase
      .map(_.pos.end)
      .flatMap(casesEndPos =>
        termTry.tokens.tokens
          .find(token =>
            token.pos.end > casesEndPos &&
              token.text == "}" &&
              maybeFinallyStartPos.forall(token.pos.start < _)
          )
      )
    pprint.log("potentialEndBraceToken is" + potentialEndBraceToken)

    if (potentialOpenBraceToken.nonEmpty) {
      for {
        openBracePose <- potentialOpenBraceToken
          .map(_.pos.toLSP.getStart)
        endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getEnd)
        if termTry.allowBracelessSyntax
      } yield createCodeActionForGoingBraceless(
        path,
        expectedBraceStartPos = openBracePose,
        expectedBraceEndPose = endBracePos,
        bracelessStart = "",
        bracelessEnd = "",
        codeActionSubjectTitle
      )
    } else { // does not have braces
      for {
        bracePose <- maybeCatchToken
          .map(_.pos.toLSP.getEnd)
        endBracePose <- maybeLastCase.map(_.pos.toLSP.getEnd)
        indentation = getIndentationForPositionInDocument(termTry.pos, document)
      } yield createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = endBracePose,
        bracelessStart = "",
        bracelessEnd = "",
        indentation = indentation,
        document = document,
        codeActionSubjectTitle = codeActionSubjectTitle
      )
    }
  }

  private def createCodeActionForTermMatch(
      termMatch: Term.Match,
      path: AbsolutePath,
      document: String,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val maybeMatchToken = util
      .Try(
        termMatch.tokens
          .filter(token =>
            token.pos.end > termMatch.expr.pos.end && token.text == "match"
          )
          .minBy(_.pos.end)
      )
      .toOption
    val potentialOpenBraceToken = maybeMatchToken
      .flatMap(matchToken =>
        util
          .Try(
            termMatch.tokens
              .filter(token =>
                token.pos.end > matchToken.pos.end && token.text == "{"
              )
              .minBy(_.pos.end)
          )
          .toOption
      )
    val maybeLastCaseEndToken =
      util
        .Try(termMatch.cases.flatMap(_.tokens.tokens).maxBy(_.pos.end))
        .toOption
    val potentialEndBraceToken = maybeLastCaseEndToken
      .map(_.pos.end)
      .flatMap(casesEndPos =>
        termMatch.tokens
          .find(token =>
            token.pos.end > casesEndPos &&
              token.text == "}"
          )
      // .minBy(_.pos.end)

      )
    if (potentialOpenBraceToken.nonEmpty) {
      for {
        openBracePose <- potentialOpenBraceToken
          .map(_.pos.toLSP.getStart)
        endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getStart)
        if termMatch.allowBracelessSyntax
      } yield createCodeActionForGoingBraceless(
        path,
        expectedBraceStartPos = openBracePose,
        expectedBraceEndPose = endBracePos,
        bracelessStart = "",
        bracelessEnd = "",
        codeActionSubjectTitle
      )
    } else { // does not have braces
      for {
        bracePose <- maybeMatchToken
          .map(_.pos.toLSP.getEnd)
        endBracePose <- maybeLastCaseEndToken.map(_.pos.toLSP.getEnd)
        indentation = getIndentationForPositionInDocument(
          termMatch.pos,
          document
        )
      } yield createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = endBracePose,
        bracelessStart = "",
        bracelessEnd = "",
        indentation = indentation,
        document = document,
        codeActionSubjectTitle = codeActionSubjectTitle
      )
    }
  }

  /**
   * @param blockHolder      the trees which can only be braced if the type of a branch of
   *                         them is `Term.Block` and that block is braced.
   * @param path             the path to the file containing the tree. It is used in
   *                         contstructing the TextEdit of the code action
   * @param document
   * @param blockEmbraceable the branch of the main tree which can potentially have
   *                         the type `Term.Block`; so can be braced.
   * @param codeActionSubjectTitle
   * @return
   */
  private def createCodeActionForPotentialBlockHolder(
      blockHolder: Tree,
      path: AbsolutePath,
      document: String,
      blockEmbraceable: Term,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = { // the block embraceable
    val indentation =
      getIndentationForPositionInDocument(blockHolder.pos, document)

    if (isBlockEmbraceableBraced(blockEmbraceable)) {
      for {
        bracePose <- util
          .Try(blockEmbraceable.tokens.minBy(_.pos.start))
          .toOption
          .map(_.pos.toLSP.getStart)
        if blockEmbraceable.allowBracelessSyntax
      } yield createCodeActionForGoingBraceless(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = blockEmbraceable.pos.toLSP.getEnd,
        bracelessStart = "",
        bracelessEnd = "",
        codeActionSubjectTitle
      )

    } else { // does not have braces
      for {
        bracePose <- util
          .Try(blockEmbraceable.tokens.minBy(_.pos.start))
          .toOption
          .map(_.pos.start)
          .flatMap(blockEmbraceableStartPos =>
            util
              .Try(
                blockHolder.tokens.tokens
                  .filter { token =>
                    token.pos.start < blockEmbraceableStartPos && !token.text.isBlank
                  }
                  .maxBy(_.pos.start)
                  .pos
                  .toLSP
                  .getEnd
              )
              .toOption
          )
      } yield createCodeActionForGoingBraceful(
        path,
        expectedBraceStartPos = bracePose,
        expectedBraceEndPose = blockEmbraceable.pos.toLSP.getEnd,
        bracelessStart = "",
        bracelessEnd = "",
        indentation = indentation,
        document = document,
        codeActionSubjectTitle = codeActionSubjectTitle
      )
    }
  }

  private def createCodeActionForTemplateHolder(
      templateHolder: Tree,
      path: AbsolutePath,
      document: String,
      templ: Template,
      codeActionSubjectTitle: String
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
    if (hasBraces(templ)) {

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
        bracelessEnd = "",
        codeActionSubjectTitle
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
        document = document,
        codeActionSubjectTitle = codeActionSubjectTitle
      )
    }

  }

  private def createCodeActionForGoingBraceful(
      path: AbsolutePath,
      indentation: String,
      document: String,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String,
      codeActionSubjectTitle: String
  ): l.CodeAction = {
    // TODO Important: autoIndentDocument()
    //  val braceableBranchLSPPos = braceableBranch.pos.toLSP
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = new l.Position()
    braceableBranchStartEnd.setCharacter(
      expectedBraceStartPos.getCharacter + bracelessStart.length
    )
    braceableBranchStartEnd.setLine(expectedBraceStartPos.getLine)
    val startBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      "{"
    )

    val braceableBranchEndStart = expectedBraceEndPose
    val braceableBranchEnd = new l.Position()
    braceableBranchEnd.setCharacter(
      expectedBraceEndPose.getCharacter + bracelessEnd.length
    )
    braceableBranchEnd.setLine(expectedBraceEndPose.getLine)

    val endBraceTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
      s"""|
          |$indentation}""".stripMargin
    )

    val codeAction = new l.CodeAction()
    codeAction.setTitle(
      BracelessBracefulSwitchCodeAction.goBraceFul(codeActionSubjectTitle)
    )
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

  private def createCodeActionForGoingBraceless(
      path: AbsolutePath,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String,
      codeActionSubjectTitle: String
  ): l.CodeAction = {

    // TODO Important: autoIndentDocument()
    val braceableBranchStart = expectedBraceStartPos
    val braceableBranchStartEnd = new l.Position()
    braceableBranchStartEnd.setCharacter(expectedBraceStartPos.getCharacter + 1)
    braceableBranchStartEnd.setLine(expectedBraceStartPos.getLine)
    val startTextEdit = new TextEdit(
      new l.Range(braceableBranchStart, braceableBranchStartEnd),
      bracelessStart
    )

    val braceableBranchEndStart = new l.Position()
    braceableBranchEndStart.setCharacter(expectedBraceEndPose.getCharacter - 1)
    braceableBranchEndStart.setLine(expectedBraceEndPose.getLine)
    val braceableBranchEnd = expectedBraceEndPose

    val endTextEdit = new TextEdit(
      new l.Range(braceableBranchEndStart, braceableBranchEnd),
      bracelessEnd
    )
    val codeAction = new l.CodeAction()
    codeAction.setTitle(
      BracelessBracefulSwitchCodeAction.goBraceless(codeActionSubjectTitle)
    )
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

  private def createCodeActionForCasesListWrapper(
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
  def goBraceFul(subject: String): String = s"Add braces to the $subject"

  def goBraceless(subject: String): String = s"Remove braces from the $subject"
}
