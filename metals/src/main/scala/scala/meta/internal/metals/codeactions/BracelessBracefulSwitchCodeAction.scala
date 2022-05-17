package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Template
import scala.meta.Term
import scala.meta.Tree
import scala.meta._
import scala.meta.inputs.Position
import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.FormattingProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.parsing.Trees
import scala.meta.io.AbsolutePath
import scala.meta.parsers.Parsed
import scala.meta.pc.CancelToken

import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.{lsp4j => l}

class BracelessBracefulSwitchCodeAction(
    trees: Trees,
    buffers: Buffers,
    formattingProvider: FormattingProvider,
    scalaVersionSelector: ScalaVersionSelector
) extends CodeAction {
  override def kind: String = l.CodeActionKind.RefactorRewrite

  override def contribute(params: CodeActionParams, token: CancelToken)(implicit
      ec: ExecutionContext
  ): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val range = params.getRange
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
          case termWhile: Term.While =>
            createCodeActionForPotentialBlockHolder(
              termWhile,
              path,
              document,
              termWhile.body,
              "while expression"
            ).toSeq
          case _: Defn.GivenAlias => None.toSeq
          case template: Template =>
            val title = template.parent
              .collectFirst {
                case _: Defn.Enum => "enum definition"
                case _: Defn.Trait => "trait definition"
                case _: Defn.Object => "object definition"
                case _: Defn.Class => "class definition"
              }
              .getOrElse("template")
            template.parent.flatMap {
              createCodeActionForTemplateHolder(
                _,
                path,
                document,
                template,
                title
              )
            }.toSeq
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
          case _ => None
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

  private def parse(path: AbsolutePath, code: String): Parsed[Tree] = {
    val input = Input.VirtualFile(path.toURI.toString(), code)
    val dialect = scalaVersionSelector.getDialect(path)
    dialect(input).parse[Source]
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
    val maybeLastCase =
      util.Try(termTry.catchp.maxBy(_.pos.end)).toOption

    if (potentialOpenBraceToken.nonEmpty) {
      if (termTry.allowBracelessSyntax)
        createCodeActionToTakeCatchPBraceless(
          potentialOpenBraceToken,
          termTry,
          maybeLastCase,
          path,
          codeActionSubjectTitle
        )
      else None
    } else { // does not have braces
      createCodeActionToTakeCatchPBraceful(
        maybeCatchToken,
        maybeLastCase,
        termTry,
        document,
        path,
        codeActionSubjectTitle
      )
    }
  }

  private def buildCodeActionToFormatOrphanTryAndDropCatchPBraces(
      potentialOpenBraceToken: Option[Token],
      termTry: Term.Try,
      maybeLastCase: Option[Case],
      path: AbsolutePath,
      codeActionSubjectTitle: String,
      maybeFinallyStartPos: Option[Int],
      potentialEndBraceToken: Option[Token]
  ): Option[l.CodeAction] = {
    val (initialCode, maybeFormattedString, maybeFormattedTry) =
      formatOrphanTry(
        termTry,
        path
      )

    for {
      formattedString <- maybeFormattedString
      formattedTermTry <- maybeFormattedTry
      maybeFormattedFinallyStartPos = formattedTermTry.tokens
        .find { finallyToken =>
          maybeLastCase.forall(_.pos.end < finallyToken.pos.end) &&
          formattedTermTry.finallyp.forall(
            _.pos.start > finallyToken.pos.start
          ) &&
          finallyToken.text == "finally"
        }
        .map(_.pos.start)

      formattedCatchToken <- util
        .Try(
          formattedTermTry.tokens.tokens
            .filter(token =>
              token.pos.end > formattedTermTry.expr.pos.end && token.text == "catch"
            )
            .minBy(_.pos.end)
        )
        .toOption

      maybeFormattedFirstCase =
        util.Try(formattedTermTry.catchp.minBy(_.pos.start)).toOption

      formattedOpenBraceToken <-
        Try(
          formattedTermTry.tokens.tokens
            .find(token =>
              token.pos.end > formattedCatchToken.pos.end &&
                token.text == "{" &&
                maybeFormattedFirstCase.forall(token.pos.start < _.pos.start)
            )
        ).toOption.flatten

      maybeFormattedLastCase =
        util.Try(formattedTermTry.catchp.maxBy(_.pos.end)).toOption

      formattedEndBraceToken <- maybeFormattedLastCase
        .map(_.pos.end)
        .flatMap(casesEndPos =>
          formattedTermTry.tokens.tokens
            .find(token =>
              token.pos.end > casesEndPos &&
                token.text == "}" &&
                maybeFormattedFinallyStartPos.forall(token.pos.start < _)
            )
        )

      openBracePose <- potentialOpenBraceToken
        .map(_.pos.toLSP.getStart)

      formattedOpenBracePose = formattedOpenBraceToken.pos.start
      endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getEnd)

      formattedEndBracePos = formattedEndBraceToken.pos.end
    } yield
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = termTry,
          formattedTree = formattedTermTry,
          path = path,
          expectedBraceStartPos = formattedOpenBracePose,
          expectedBraceEndPose = formattedEndBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = openBracePose,
          expectedBraceEndPose = endBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  private def buildCodeActionToFormatParentedTryAndDropCatchPBraces(
      potentialOpenBraceToken: Option[Token],
      termTry: Term.Try,
      termTryParent: Tree,
      maybeLastCase: Option[Case],
      path: AbsolutePath,
      codeActionSubjectTitle: String,
      maybeFinallyStartPos: Option[Int],
      potentialEndBraceToken: Option[Token]
  ): Option[l.CodeAction] = {
    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedTry
    ) = formatParentedTry(
      termTry,
      termTryParent,
      path
    )

    for {
      formattedString <- maybeFormattedString
      formattedTermTry <- maybeFormattedTry
      formattedParent <- maybeFormattedParent
      maybeFormattedFinallyStartPos = formattedTermTry.tokens
        .find { finallyToken =>
          maybeLastCase.forall(_.pos.end < finallyToken.pos.end) &&
          formattedTermTry.finallyp.forall(
            _.pos.start > finallyToken.pos.start
          ) &&
          finallyToken.text == "finally"
        }
        .map(_.pos.start)

      formattedCatchToken <- util
        .Try(
          formattedTermTry.tokens.tokens
            .filter(token =>
              token.pos.end > formattedTermTry.expr.pos.end && token.text == "catch"
            )
            .minBy(_.pos.end)
        )
        .toOption

      maybeFormattedFirstCase =
        util.Try(formattedTermTry.catchp.minBy(_.pos.start)).toOption

      formattedOpenBraceToken <-
        Try(
          formattedTermTry.tokens.tokens
            .find(token =>
              token.pos.end > formattedCatchToken.pos.end &&
                token.text == "{" &&
                maybeFormattedFirstCase.forall(token.pos.start < _.pos.start)
            )
        ).toOption.flatten

      maybeFormattedLastCase =
        util.Try(formattedTermTry.catchp.maxBy(_.pos.end)).toOption

      formattedEndBraceToken <- maybeFormattedLastCase
        .map(_.pos.end)
        .flatMap(casesEndPos =>
          formattedTermTry.tokens.tokens
            .find(token =>
              token.pos.end > casesEndPos &&
                token.text == "}" &&
                maybeFormattedFinallyStartPos.forall(token.pos.start < _)
            )
        )

      openBracePose <- potentialOpenBraceToken
        .map(_.pos.toLSP.getStart)

      formattedOpenBracePose = formattedOpenBraceToken.pos.start
      endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getEnd)

      formattedEndBracePos = formattedEndBraceToken.pos.end

    } yield
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = termTryParent,
          formattedTree = formattedParent,
          path = path,
          expectedBraceStartPos = formattedOpenBracePose,
          expectedBraceEndPose = formattedEndBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = openBracePose,
          expectedBraceEndPose = endBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  private def createCodeActionToTakeCatchPBraceless(
      potentialOpenBraceToken: Option[Token],
      termTry: Term.Try,
      maybeLastCase: Option[Case],
      path: AbsolutePath,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val maybeFinallyStartPos = termTry.tokens
      .find { finallyToken =>
        maybeLastCase.forall(_.pos.end < finallyToken.pos.end) &&
        termTry.finallyp.forall(_.pos.start > finallyToken.pos.start) &&
        finallyToken.text == "finally"
      }
      .map(_.pos.start)

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

    if (termTry.parent.isEmpty)
      buildCodeActionToFormatOrphanTryAndDropCatchPBraces(
        potentialOpenBraceToken,
        termTry,
        maybeLastCase,
        path,
        codeActionSubjectTitle,
        maybeFinallyStartPos,
        potentialEndBraceToken
      )
    else
      buildCodeActionToFormatParentedTryAndDropCatchPBraces(
        potentialOpenBraceToken = potentialOpenBraceToken,
        termTry = termTry,
        termTryParent = termTry.parent.get,
        maybeLastCase = maybeLastCase,
        path = path,
        codeActionSubjectTitle = codeActionSubjectTitle,
        maybeFinallyStartPos = maybeFinallyStartPos,
        potentialEndBraceToken = potentialEndBraceToken
      )
  }

  private def createCodeActionToTakeCatchPBraceful(
      maybeCatchToken: Option[Token],
      maybeLastCase: Option[Case],
      termTry: Term.Try,
      document: String,
      path: AbsolutePath,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
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
      codeActionSubjectTitle = codeActionSubjectTitle,
      maybeEndMarkerPos = None
    )
  }

  private def createCodeActionForTermMatch(
      termMatch: Term.Match,
      path: AbsolutePath,
      document: String,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {

    val maybeMatchToken = util
      .Try(
        termMatch.tokens.tokens
          .filter(token =>
            token.pos.end > termMatch.expr.pos.end && token.text == "match"
          )
          .minBy(_.pos.end)
      )
      .toOption
    val maybeFirstCase =
      Try(termMatch.cases.minBy(_.pos.start)).toOption
    val potentialOpenBraceToken = maybeMatchToken
      .flatMap(matchToken =>
        Try(
          termMatch.tokens.tokens
            .find(token =>
              token.pos.end > matchToken.pos.end &&
                token.text == "{" &&
                maybeFirstCase.forall(token.pos.start < _.pos.start)
            )
        ).toOption.flatten
      )
    val maybeLastCase =
      Try(termMatch.cases.maxBy(_.pos.end)).toOption

    if (potentialOpenBraceToken.nonEmpty) {
      if (termMatch.allowBracelessSyntax)
        createCodeActionToTakeTermMatchBraceless(
          maybeLastCase,
          termMatch,
          potentialOpenBraceToken,
          path,
          codeActionSubjectTitle
        )
      else None
    } else { // does not have braces
      createCodeActionToTakeTermMatchBraceful(
        maybeMatchToken,
        termMatch,
        maybeLastCase,
        document,
        path,
        codeActionSubjectTitle
      )
    }
  }

  private def createCodeActionToTakeTermMatchBraceless(
      maybeLastCase: Option[Case],
      termMatch: Term.Match,
      potentialOpenBraceToken: Option[Token],
      path: AbsolutePath,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val potentialEndBraceToken = maybeLastCase
      .map(_.pos.end)
      .flatMap(casesEndPos =>
        termMatch.tokens.tokens
          .find(token =>
            token.pos.end > casesEndPos &&
              token.text == "}"
          )
      )

    if (termMatch.parent.isEmpty) {
      creatCodeActionToTakeOrphanTermMatchBraceless(
        termMatch,
        potentialOpenBraceToken,
        potentialEndBraceToken,
        path,
        codeActionSubjectTitle
      )
    } else
      createCodeActionToTakeParentedTermMatchBraceless(
        termMatch,
        potentialOpenBraceToken,
        potentialEndBraceToken,
        path,
        codeActionSubjectTitle,
        termMatch.parent.get
      )

  }

  private def formatParentedTermMatch(
      termMatch: Term.Match,
      termMatchParent: Tree,
      path: AbsolutePath
  ): (String, Option[String], Option[Tree], Option[Term.Match]) = {
    val initialCode = termMatchParent.toString()

    val maybeFormattedString =
      formattingProvider.programmaticallyFormat(path, initialCode)

    val maybeFormattedParent: Option[Tree] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(_, _, _) =>
          None
        case Parsed.Success(tree) =>
          Some(tree)
      }
    val maybeTermMatchIndex = Try(
      termMatchParent.children.indexOf(termMatch)
    ).toOption

    val maybeFormattedTermMatch =
      for {
        formattedParent <- maybeFormattedParent
        termMatchIndex <- maybeTermMatchIndex
      } yield {
        Try(
          formattedParent.children(termMatchIndex).asInstanceOf[Term.Match]
        ).toOption
      }

    (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedTermMatch.flatten
    )
  }

  private def formatOrphanTermMatch(
      termMatch: Term.Match,
      path: AbsolutePath
  ): (String, Option[String], Option[Term.Match]) = {
    val initialCode = termMatch.toString()

    val maybeFormattedString =
      formattingProvider.programmaticallyFormat(path, initialCode)

    val maybeFormattedMatch: Option[Term.Match] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(_, _, _) =>
          None
        case Parsed.Success(tree) =>
          Some(tree.asInstanceOf[Term.Match])
      }

    (initialCode, maybeFormattedString, maybeFormattedMatch)
  }

  private def creatCodeActionToTakeOrphanTermMatchBraceless(
      termMatch: Term.Match,
      potentialOpenBraceToken: Option[Token],
      potentialEndBraceToken: Option[Token],
      path: AbsolutePath,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val (initialCode, maybeFormattedString, maybeFormattedTermMatch) =
      formatOrphanTermMatch(termMatch, path)
    for {
      formattedTermMatch <- maybeFormattedTermMatch
      formattedString <- maybeFormattedString
      formattedMatchToken <- util
        .Try(
          formattedTermMatch.tokens.tokens
            .filter(token =>
              token.pos.end > formattedTermMatch.expr.pos.end && token.text == "match"
            )
            .minBy(_.pos.end)
        )
        .toOption
      maybeFormattedFirstCase = Try(
        formattedTermMatch.cases.minBy(_.pos.start)
      ).toOption
      formattedOpenBraceToken <- Try(
        formattedTermMatch.tokens.tokens
          .find(token =>
            token.pos.end > formattedMatchToken.pos.end &&
              token.text == "{" &&
              maybeFormattedFirstCase.forall(token.pos.start < _.pos.start)
          )
      ).toOption.flatten

      formattedOpenBracePos = formattedOpenBraceToken.pos.start
      formattedEndBracePos = formattedOpenBraceToken.pos.end
      openBracePose <- potentialOpenBraceToken
        .map(_.pos.toLSP.getStart)
      endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getEnd)

    } yield {
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = termMatch,
          formattedTree = formattedTermMatch,
          path = path,
          expectedBraceStartPos = formattedOpenBracePos,
          expectedBraceEndPose = formattedEndBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = openBracePose,
          expectedBraceEndPose = endBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
    }
  }

  private def createCodeActionToTakeParentedTermMatchBraceless(
      termMatch: Term.Match,
      potentialOpenBraceToken: Option[Token],
      potentialEndBraceToken: Option[Token],
      path: AbsolutePath,
      codeActionSubjectTitle: String,
      termMatchParent: Tree
  ): Option[l.CodeAction] = {
    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedTermMatchParent,
      maybeFormattedTermMatch
    ) = formatParentedTermMatch(termMatch, termMatchParent, path)
    for {
      formattedTermMatchParent <- maybeFormattedTermMatchParent
      formattedTermMatch <- maybeFormattedTermMatch
      formattedString <- maybeFormattedString
      formattedMatchToken <- util
        .Try(
          formattedTermMatch.tokens.tokens
            .filter(token =>
              token.pos.end > formattedTermMatch.expr.pos.end && token.text == "match"
            )
            .minBy(_.pos.end)
        )
        .toOption
      maybeFormattedFirstCase = Try(
        formattedTermMatch.cases.minBy(_.pos.start)
      ).toOption
      formattedOpenBraceToken <- Try(
        formattedTermMatch.tokens.tokens
          .find(token =>
            token.pos.end > formattedMatchToken.pos.end &&
              token.text == "{" &&
              maybeFormattedFirstCase.forall(token.pos.start < _.pos.start)
          )
      ).toOption.flatten

      formattedOpenBracePos = formattedOpenBraceToken.pos.start
      formattedEndBracePos = formattedOpenBraceToken.pos.end
      openBracePose <- potentialOpenBraceToken
        .map(_.pos.toLSP.getStart)
      endBracePos <- potentialEndBraceToken.map(_.pos.toLSP.getEnd)

    } yield {
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = termMatchParent,
          formattedTree = formattedTermMatchParent,
          path = path,
          expectedBraceStartPos = formattedOpenBracePos,
          expectedBraceEndPose = formattedEndBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = openBracePose,
          expectedBraceEndPose = endBracePos,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
    }
  }

  private def createCodeActionToTakeTermMatchBraceful(
      maybeMatchToken: Option[Token],
      termMatch: Term.Match,
      maybeLastCase: Option[Case],
      document: String,
      path: AbsolutePath,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    for {
      bracePose <- maybeMatchToken
        .map(_.pos.toLSP.getEnd)
      endBracePose <- maybeLastCase.map(_.pos.toLSP.getEnd)
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
      codeActionSubjectTitle = codeActionSubjectTitle,
      maybeEndMarkerPos = None
    )
  }

  private def createCodeActionToTakeOrphanBlockHolderBraceless(
      blockHolder: Tree,
      path: AbsolutePath,
      blockEmbraceable: Term,
      codeActionSubjectTitle: String,
      originalIndentation: String
  ): Option[l.CodeAction] = {

    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedBlockHolder,
      maybeFormattedBlockEmbraceable
    ) =
      formatOrphanTree(blockHolder, path, blockEmbraceable, originalIndentation)

    for {
      formattedBlockEmbraceable <- maybeFormattedBlockEmbraceable
      formattedBlockHolder <- maybeFormattedBlockHolder
      formattedString <- maybeFormattedString
      bracePose <- util
        .Try(formattedBlockEmbraceable.tokens.minBy(_.pos.start))
        .toOption
        .map(_.pos.start)
      defaultBracePos <- util
        .Try(blockEmbraceable.tokens.minBy(_.pos.start))
        .toOption
        .map(_.pos.toLSP.getStart)
    } yield
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          path = path,
          expectedBraceStartPos = bracePose,
          expectedBraceEndPose = formattedBlockEmbraceable.pos.end,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle = codeActionSubjectTitle,
          formattedTree = formattedBlockHolder,
          originalTree = blockHolder
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = defaultBracePos,
          expectedBraceEndPose = blockEmbraceable.pos.toLSP.getEnd,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  def formatParentedTree(
      tree: Tree,
      treeParent: Tree,
      path: AbsolutePath,
      branch: Tree,
      parentIndentation: String
  ): (String, Option[String], Option[Tree], Option[Tree], Option[Tree]) = {
    val initialCode = treeParent.toString()

    val maybeFormattedString =
      getIndentedFormattedCode(path, initialCode, parentIndentation)

    val maybeTreeIndex = Try(treeParent.children.indexOf(tree)).toOption
    pprint.log(s"the tree index for \n$tree is:" + maybeTreeIndex)

    val maybeFormattedParent: Option[Tree] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(position, string, exception) =>
          pprint.log(s"${exception.getMessage} at $position : $string")
          None
        case Parsed.Success(tree) =>
          Some(tree)
      }
    pprint.log(s"maybeFormattedParent is \n " + maybeFormattedParent)

    val maybeFormattedTree = {
      for {
        formattedParent <- maybeFormattedParent
        treeIndex <- maybeTreeIndex
      } yield Try(formattedParent.children(treeIndex)).toOption
    }.flatten

    pprint.log(s"maybeFormattedTree is \n " + maybeFormattedTree)

    val maybeBranchIndex = Try(tree.children.indexOf(branch)).toOption
    val maybeFormattedBranch: Option[Tree] = {
      for {
        formattedTree <- maybeFormattedTree
        branchIndex <- maybeBranchIndex
      } yield Try(formattedTree.children(branchIndex)).toOption
    }.flatten

    pprint.log(s"maybeFormattedBranch is \n " + maybeFormattedBranch)

    (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedTree,
      maybeFormattedBranch
    )
  }

  private def createCodeActionToTakeParentedBlockHolderBraceless(
      blockHolder: Tree,
      blockHolderParent: Tree,
      path: AbsolutePath,
      blockEmbraceable: Term,
      codeActionSubjectTitle: String,
      parentIndentation: String
  ): Option[l.CodeAction] = {
    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedBlockHolder,
      maybeFormattedBlockEmbraceable
    ) = formatParentedTree(
      blockHolder,
      blockHolderParent,
      path,
      blockEmbraceable,
      parentIndentation
    )

    pprint.log("the formatted parent is\n" + maybeFormattedParent.get)
    pprint.log(
      "the formatted block holder is\n" + maybeFormattedBlockHolder.get
    )
    pprint.log(
      "the formatted block embraceable is\n" + maybeFormattedBlockEmbraceable.get
    )

    for {
      formattedBlockEmbraceable <- maybeFormattedBlockEmbraceable
      formattedParent <- maybeFormattedParent
      formattedBracePose <- util
        .Try(formattedBlockEmbraceable.tokens.minBy(_.pos.start))
        .toOption
        .map(_.pos.start)
      formattedString <- maybeFormattedString
      defaultBracePos <- util
        .Try(blockEmbraceable.tokens.minBy(_.pos.start))
        .toOption
        .map(_.pos.toLSP.getStart)
    } yield
      if (formattedString != initialCode) {
        pprint.log(
          "creating code action for the fomatted block holder parent:\n" + formattedParent
        )
        createCodeActionForGoingBracelessWithFormatting(
          path = path,
          expectedBraceStartPos = formattedBracePose,
          expectedBraceEndPose = formattedBlockEmbraceable.pos.end,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle = codeActionSubjectTitle,
          formattedTree = formattedParent,
          originalTree = blockHolderParent
        )
      } else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = defaultBracePos,
          expectedBraceEndPose = blockEmbraceable.pos.toLSP.getEnd,
          bracelessStart = "",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  private def creatCodeActionToTakePotentialBlockHolderBraceless(
      blockHolder: Tree,
      path: AbsolutePath,
      blockEmbraceable: Term,
      codeActionSubjectTitle: String,
      originalIndentation: String,
      maybeParentIndentation: Option[String]
  ): Option[l.CodeAction] = {
    if (blockHolder.parent.isEmpty)
      createCodeActionToTakeOrphanBlockHolderBraceless(
        blockHolder,
        path,
        blockEmbraceable,
        codeActionSubjectTitle,
        originalIndentation
      )
    else {
      for {
        parent <- blockHolder.parent
        parentIndentation <- maybeParentIndentation
      } yield createCodeActionToTakeParentedBlockHolderBraceless(
        blockHolder,
        parent,
        path,
        blockEmbraceable,
        codeActionSubjectTitle,
        parentIndentation
      )
    }.flatten
  }

  private def createCodeActionToTakePotentialBlockHolderBraceful(
      blockHolder: Tree,
      path: AbsolutePath,
      document: String,
      blockEmbraceable: Term,
      codeActionSubjectTitle: String,
      indentation: String
  ) = {

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
      codeActionSubjectTitle = codeActionSubjectTitle,
      maybeEndMarkerPos = maybeGetEndMarkerPos(blockHolder)
    )
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
  ): Option[l.CodeAction] = {
    val indentation =
      getIndentationForPositionInDocument(blockHolder.pos, document)
    val maybeParentIndentation = blockHolder.parent.map { parent =>
      getIndentationForPositionInDocument(parent.pos, document)
    }
    if (isBlockEmbraceableBraced(blockEmbraceable)) {
      if (blockEmbraceable.allowBracelessSyntax)
        creatCodeActionToTakePotentialBlockHolderBraceless(
          blockHolder,
          path,
          blockEmbraceable,
          codeActionSubjectTitle,
          indentation,
          maybeParentIndentation
        )
      else None
    } else // does not have braces
      createCodeActionToTakePotentialBlockHolderBraceful(
        blockHolder,
        path,
        document,
        blockEmbraceable,
        codeActionSubjectTitle,
        indentation
      )
  }

  private def maybeGetEndMarkerPos(tree: Tree): Option[Position] =
    tree.parent
      .flatMap(parent =>
        parent.children.dropWhile(_ != tree).tail.headOption.collectFirst {
          case endMarker: Term.EndMarker => endMarker
        }
      )
      .map(_.pos)

  private def createCodeActionForTemplateHolder(
      templateHolder: Tree,
      path: AbsolutePath,
      document: String,
      templ: Template,
      codeActionSubjectTitle: String
  ): Option[l.CodeAction] = {
    val indentation =
      getIndentationForPositionInDocument(templateHolder.pos, document)
    val maybeParentIndentation = templateHolder.parent.map(parent =>
      getIndentationForPositionInDocument(parent.pos, document)
    )
    val expectedBraceStartPos = util
      .Try {
        val lastInit = templ.inits.maxBy(init => init.pos.end)
        lastInit.pos.end
      }
      .getOrElse(templ.pos.start)
    if (hasBraces(templ)) {
      if (templ.allowBracelessSyntax)
        createCodeActionToTakeTemplateHolderBraceless(
          templateHolder,
          path,
          templ,
          codeActionSubjectTitle,
          expectedBraceStartPos,
          indentation,
          maybeParentIndentation
        )
      else None
    } else // does not have braces
      createCodeActionToTakeTemplateHolderBraceful(
        templateHolder,
        path,
        document,
        templ,
        codeActionSubjectTitle,
        expectedBraceStartPos,
        indentation
      )
  }

  private def createCodeActionToTakeTemplateHolderBraceless(
      templateHolder: Tree,
      path: AbsolutePath,
      templ: Template,
      codeActionSubjectTitle: String,
      expectedBraceStartPos: Int,
      indentation: String,
      maybeParentIndentation: Option[String]
  ): Option[l.CodeAction] = {
    if (templateHolder.parent.isEmpty)
      createCodeActionToTakeOrphanTemplateHolderBraceless(
        templateHolder,
        path,
        templ,
        codeActionSubjectTitle,
        expectedBraceStartPos,
        indentation
      )
    else {
      {
        for {
          parent <- templateHolder.parent
          parentIndentaion <- maybeParentIndentation
        } yield createCodeActionToTakeParentedTemplateHolderBraceless(
          templateHolder,
          parent,
          path,
          templ,
          codeActionSubjectTitle,
          expectedBraceStartPos,
          parentIndentaion
        )
      }.flatten
    }

  }
  private def getIndentedFormattedCode(
      path: AbsolutePath,
      initialCode: String,
      startLineIndentaion: String
  ): Option[String] = {
    formattingProvider.programmaticallyFormat(path, initialCode).flatMap {
      formattedString =>
        formattedString.split("\n").toList match {
          case (firstLine: String) :: subsequentLines =>
            Some(
              {
                firstLine +: subsequentLines.map(startLineIndentaion + _)
              }.mkString("\n")
            )
          case (firstLine: String) :: Nil => Some(firstLine)
          case Nil => None
        }
    }
  }

  private def formatOrphanTree(
      tree: Tree,
      path: AbsolutePath,
      branch: Tree,
      originalIndentation: String
  ): (String, Option[String], Option[Tree], Option[Tree]) = {
    val initialCode = tree.toString()

    val maybeFormattedString =
      getIndentedFormattedCode(path, initialCode, originalIndentation)

    val maybeFormattedTree: Option[Tree] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(_, _, _) =>
          None
        case Parsed.Success(tree) =>
          Some(tree)
      }

    val maybeBranchIndex = Try(tree.children.indexOf(branch)).toOption

    val maybeFormattedBranch: Option[Tree] = {
      for {
        formattedTree <- maybeFormattedTree
        branchIndex <- maybeBranchIndex
      } yield Try(formattedTree.children(branchIndex)).toOption
    }.flatten

    (
      initialCode,
      maybeFormattedString,
      maybeFormattedTree,
      maybeFormattedBranch
    )
  }

  private def formatOrphanTry(
      termTry: Term.Try,
      path: AbsolutePath
  ): (String, Option[String], Option[Term.Try]) = {
    val initialCode = termTry.toString()

    val maybeFormattedString =
      formattingProvider.programmaticallyFormat(path, initialCode)

    val maybeFormattedTry: Option[Term.Try] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(_, _, _) =>
          None
        case Parsed.Success(tree) =>
          Some(tree.asInstanceOf[Term.Try])
      }

    (initialCode, maybeFormattedString, maybeFormattedTry)
  }

  private def formatParentedTry(
      termTry: Term.Try,
      termTryParent: Tree,
      path: AbsolutePath
  ): (String, Option[String], Option[Tree], Option[Term.Try]) = {
    val initialCode = termTryParent.toString()

    val maybeFormattedString =
      formattingProvider.programmaticallyFormat(path, initialCode)

    val maybeFormattedParent: Option[Tree] =
      maybeFormattedString.map(parse(path, _)).flatMap {
        case Parsed.Error(_, _, _) =>
          None
        case Parsed.Success(tree) =>
          Some(tree)
      }
    val maybeTermTryIndex = Try(
      termTryParent.children.indexOf(termTry)
    ).toOption

    val maybeFormattedTry =
      for {
        formattedParent <- maybeFormattedParent
        termTryIndex <- maybeTermTryIndex
      } yield {
        Try(
          formattedParent.children(termTryIndex).asInstanceOf[Term.Try]
        ).toOption
      }

    (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedTry.flatten
    )
  }

  private def createCodeActionToTakeOrphanTemplateHolderBraceless(
      templateHolder: Tree,
      path: AbsolutePath,
      templ: Template,
      codeActionSubjectTitle: String,
      defaultExpectedBraceStartPos: Int,
      indentation: String
  ): Option[l.CodeAction] = {

    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedTemplateHolder,
      maybeFormattedTempl
    ) = formatOrphanTree(
      templateHolder,
      path,
      templ,
      indentation
    )

    for {
      formattedTemplateHolder <- maybeFormattedTemplateHolder
      formattedTempl <- maybeFormattedTempl

      expectedBraceStartPosFormatted = Try(
        formattedTempl.asInstanceOf[Template].inits.maxBy(_.pos.end).pos.end
      ).toOption.getOrElse(formattedTempl.pos.start)

      bracePosFormatted <- formattedTempl.tokens
        .find(token =>
          token.text == "{" && token.pos.start >= expectedBraceStartPosFormatted
        )
        .map(_.pos.start)

      formattedString <- maybeFormattedString

      bracePose <- templ.tokens
        .find(token =>
          token.text == "{" && token.pos.start >= defaultExpectedBraceStartPos
        )
        .map(_.pos.toLSP.getStart)

    } yield
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = templateHolder,
          formattedTree = formattedTemplateHolder,
          path,
          bracePosFormatted,
          formattedTempl.pos.end,
          bracelessStart = ":",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = bracePose,
          expectedBraceEndPose = templ.pos.toLSP.getEnd,
          bracelessStart = ":",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  private def createCodeActionToTakeParentedTemplateHolderBraceless(
      templateHolder: Tree,
      templateHoderParent: Tree,
      path: AbsolutePath,
      templ: Template,
      codeActionSubjectTitle: String,
      defaultExpectedBraceStartPos: Int,
      parentIndentation: String
  ): Option[l.CodeAction] = {

    val (
      initialCode,
      maybeFormattedString,
      maybeFormattedParent,
      maybeFormattedTemplateHolder,
      maybeFormattedTemplate
    ) = formatParentedTree(
      templateHolder,
      templateHoderParent,
      path,
      templ,
      parentIndentation
    )
    for {
      formattedString <- maybeFormattedString
      formattedParent <- maybeFormattedParent
      formattedTemplate <- maybeFormattedTemplate
      formattedExpectedBraceStartPos =
        Try(
          formattedTemplate
            .asInstanceOf[Template]
            .inits
            .maxBy(_.pos.end)
            .pos
            .end
        ).toOption.getOrElse(formattedTemplate.pos.start)

      bracePose <- templ.tokens
        .find(token =>
          token.text == "{" && token.pos.start >= defaultExpectedBraceStartPos
        )
        .map(_.pos.toLSP.getStart)

      bracePoseFormatted <- formattedTemplate.tokens
        .find(token =>
          token.text == "{" && token.pos.start >= formattedExpectedBraceStartPos
        )
        .map(_.pos.start)
    } yield
      if (formattedString != initialCode)
        createCodeActionForGoingBracelessWithFormatting(
          originalTree = templateHoderParent,
          formattedTree = formattedParent,
          path,
          bracePoseFormatted,
          formattedTemplate.pos.end,
          bracelessStart = ":",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
      else
        createCodeActionForGoingBracelessWithoutFormatting(
          path,
          expectedBraceStartPos = bracePose,
          expectedBraceEndPose = templ.pos.toLSP.getEnd,
          bracelessStart = ":",
          bracelessEnd = "",
          codeActionSubjectTitle
        )
  }

  private def createCodeActionToTakeTemplateHolderBraceful(
      templateHolder: Tree,
      path: AbsolutePath,
      document: String,
      templ: Template,
      codeActionSubjectTitle: String,
      expectedBraceStartPos: Int,
      indentation: String
  ): Option[l.CodeAction] = {

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
      codeActionSubjectTitle = codeActionSubjectTitle,
      maybeEndMarkerPos = maybeGetEndMarkerPos(templateHolder)
    )
  }

  private def createCodeActionForGoingBraceful(
      path: AbsolutePath,
      indentation: String,
      document: String,
      expectedBraceStartPos: l.Position,
      expectedBraceEndPose: l.Position,
      bracelessStart: String,
      bracelessEnd: String,
      codeActionSubjectTitle: String,
      maybeEndMarkerPos: Option[Position]
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
    val maybeEndMarkerEraserTextEdit = maybeEndMarkerPos.map { endMarkerPos =>
      new TextEdit(
        new l.Range(endMarkerPos.toLSP.getStart, endMarkerPos.toLSP.getEnd),
        ""
      )
    }.toList

    val codeAction = new l.CodeAction()
    codeAction.setTitle(
      BracelessBracefulSwitchCodeAction.goBraceFul(codeActionSubjectTitle)
    )
    codeAction.setKind(this.kind)
    codeAction.setEdit(
      new l.WorkspaceEdit(
        Map(
          path.toURI.toString -> (List(
            startBraceTextEdit,
            endBraceTextEdit
          ) ++ maybeEndMarkerEraserTextEdit).asJava
        ).asJava
      )
    )
    codeAction
  }

  private def createCodeActionForGoingBracelessWithoutFormatting(
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

  private def createCodeActionForGoingBracelessWithFormatting(
      originalTree: Tree,
      formattedTree: Tree,
      path: AbsolutePath,
      expectedBraceStartPos: Int,
      expectedBraceEndPose: Int,
      bracelessStart: String,
      bracelessEnd: String,
      codeActionSubjectTitle: String
  ): l.CodeAction = {
    val textEditText = formattedTree
      .toString()
      .patch(expectedBraceStartPos, bracelessStart, 1)
      .patch(
        expectedBraceEndPose - 1 - 1

        /** -1  for the start brace that was removed* */
          + bracelessStart.length,
        bracelessEnd,
        1
      )

    val textEditStart = originalTree.pos.toLSP.getStart
    val textEditEnd = originalTree.pos.toLSP.getEnd

    val textEdit = new TextEdit(
      new l.Range(textEditStart, textEditEnd),
      textEditText
    )

    val codeAction = new l.CodeAction()
    codeAction.setTitle(
      BracelessBracefulSwitchCodeAction.goBracelessWithFormatting(
        codeActionSubjectTitle
      )
    )
    codeAction.setKind(this.kind)
    codeAction.setEdit(
      new l.WorkspaceEdit(
        Map(
          path.toURI.toString -> List(textEdit).asJava
        ).asJava
      )
    )
    codeAction

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

  private def findFormattedTreeInFormattedFileTree(
      tree: Tree,
      formattedFileTree: Tree
  ): Option[(Tree, Tree)] = {
    if (tree.parent.isEmpty)
      Some(formattedFileTree, tree)
    else {
      for {
        parent <- tree.parent
        treeIndex <- Try(parent.children.indexOf(tree)).toOption
        (formattedParent, candidateUnformattedFileTree) <-
          findFormattedTreeInFormattedFileTree(parent, formattedFileTree)
      } yield {
        Try(formattedParent.children(treeIndex), parent).toOption
      }
    }.flatten
  }

  def treeIsWellIndented(df: Defn.Def, document: String): Unit = {
    val indentation = Indentation(
      getIndentationForPositionInDocument(df.pos, document)
    )

    def correctTokensIndent(
        tokens: Iterator[Token],
        currentIndent: Indentation,
        countWhiteSpace: Boolean
    ): Boolean = {
      if (!tokens.hasNext) true
      else {
        tokens.next() match {
          case _: Token.LF =>
            correctTokensIndent(tokens, Indentation(), countWhiteSpace = true)
          case _: Token.Space if countWhiteSpace =>
            correctTokensIndent(
              tokens,
              currentIndent = Indentation(currentIndent.indentation :+ ' '),
              countWhiteSpace = true
            )
          case _: Token.Tab if countWhiteSpace =>
            correctTokensIndent(
              tokens,
              currentIndent = Indentation(currentIndent.indentation :+ '\t'),
              countWhiteSpace = true
            )
          // is the last brace ending the block
          case _: Token.RightBrace if !tokens.hasNext => true
          case _ if (currentIndent <= indentation) => false
          case _ =>
            correctTokensIndent(
              tokens,
              Indentation(maxValue = true),
              countWhiteSpace = false
            )
        }
      }
    }

    val res = df.body match {
      case b: Term.Block =>
        correctTokensIndent(
          b.tokens.iterator,
          currentIndent = Indentation(maxValue = true),
          countWhiteSpace = false
        )
      case _ =>
        false
    }

    println(res)

  }

}

object BracelessBracefulSwitchCodeAction {
  def goBraceFul(subject: String): String = s"Add braces to the $subject"

  def goBraceless(subject: String): String = s"Remove braces from the $subject"

  def goBracelessWithFormatting(subject: String): String =
    s"Format the $subject and remove braces from it"
}
