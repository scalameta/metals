package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.CodeAction
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.parsing.Trees
import scala.meta.pc.CancelToken
import scala.meta.tokens.Token

import org.eclipse.{lsp4j => l}

class StringActions(buffers: Buffers) extends CodeAction {

  override def kind: String = l.CodeActionKind.Refactor

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = {

    val uri = params.getTextDocument.getUri
    val path = uri.toAbsolutePath
    val range = params.getRange
    Future
      .successful {
        val tokenized = buffers
          .get(path)
          .flatMap(source =>
            Trees.defaultTokenizerDialect(source).tokenize.toOption
          )
        tokenized match {
          case Some(tokens) => {
            val stripMarginActions = tokens
              .filter(t =>
                t.pos.startLine == range.getStart.getLine
                  && t.pos.endLine == range.getEnd.getLine
              )
              .collect {
                case token: Token.Constant.String
                    if (token.pos.toLSP.overlapsWith(range)
                      && isNotTripleQuote(token)) =>
                  token
                case start: Token.Interpolation.Start
                    if (start.pos.toLSP.getStart.getCharacter <= range.getStart.getCharacter
                      && isNotTripleQuote(start)) =>
                  start
                case end: Token.Interpolation.End
                    if (end.pos.toLSP.getEnd.getCharacter >= range.getEnd.getCharacter
                      && isNotTripleQuote(end)) =>
                  end
              }
              .toList match {
              case (t: Token.Constant.String) :: _ =>
                List(stripMarginAction(uri, t.pos.toLSP))
              case _ :: (t: Token.Constant.String) :: _ =>
                List(stripMarginAction(uri, t.pos.toLSP))
              case (s: Token.Interpolation.Start) :: (e: Token.Interpolation.End) :: _ =>
                List(
                  stripMarginAction(
                    uri,
                    new l.Range(s.pos.toLSP.getStart, e.pos.toLSP.getEnd),
                  )
                )
              case _ =>
                Nil
            }

            val interpolationActions = tokens.collect {
              case token: Token.Constant.String
                  if token.pos.toLSP.overlapsWith(range) =>
                interpolateAction(uri, token)
            }.toList

            val removeInterpolationActions = tokens.zipWithIndex
              .flatMap {
                case (start: Token.Interpolation.Start, i: Int)
                    if (i + 2 < tokens.length
                      && tokens(i + 2).is[Token.Interpolation.End]) =>
                  def overlaps = List(start, tokens(i + 1), tokens(i + 2))
                    .exists(_.pos.toLSP.overlapsWith(range))
                  if (overlaps) {
                    val lspRange = start.pos.toLSP
                    val editRange =
                      new l.Range(lspRange.getStart, lspRange.getEnd)
                    Some(removeInterpolationAction(uri, editRange))
                  } else {
                    None
                  }
                case _ => None
              }

            stripMarginActions ++ interpolationActions ++ removeInterpolationActions
          }

          case None => Nil
        }
      }
  }

  def stripMarginAction(
      uri: String,
      range: l.Range,
  ): l.CodeAction = {
    range.getStart.setCharacter(range.getStart.getCharacter + 1)
    val startRange = new l.Range(range.getStart, range.getStart)

    val endRange = new l.Range(range.getEnd, range.getEnd)

    val edits = List(
      new l.TextEdit(startRange, quotify("''|")),
      new l.TextEdit(endRange, quotify("''.stripMargin")),
    )

    codeRefactorAction(StringActions.multilineTitle, uri, edits)
  }

  def interpolateAction(
      uri: String,
      token: Token.Constant.String,
  ): l.CodeAction = {
    val range = token.pos.toLSP
    val start = range.getStart()
    val dollarIndexes = token.value.indicesOf("$")
    lazy val newlineIndexes = token.value.indicesOf("\n")
    val dollarEdits = for (dolarIndex <- dollarIndexes) yield {
      val newlinesBeforeDolar = newlineIndexes.takeWhile(_ < dolarIndex)
      val updatedCharacter =
        if (newlinesBeforeDolar.isEmpty) start.getCharacter() + dolarIndex + 1
        else dolarIndex - newlinesBeforeDolar.lastOption.getOrElse(0)
      val dollarPos =
        new l.Position(
          start.getLine() + newlinesBeforeDolar.size,
          updatedCharacter,
        )
      val dollarRange = new l.Range(dollarPos, dollarPos)
      new l.TextEdit(dollarRange, "$")
    }

    val startRange = new l.Range(start, start)

    val edits = new l.TextEdit(startRange, "s") :: dollarEdits

    codeRefactorAction(StringActions.interpolationTitle, uri, edits)
  }

  def removeInterpolationAction(
      uri: String,
      range: l.Range,
  ): l.CodeAction = {
    range.getStart.setCharacter(range.getStart.getCharacter - 1)
    range.getEnd.setCharacter(range.getStart.getCharacter + 1)

    val edits = List(
      new l.TextEdit(range, "")
    )

    codeRefactorAction(StringActions.removeInterpolationTitle, uri, edits)
  }

  def quotify(str: String): String = str.replace("'", "\"")

  def isNotTripleQuote(token: Token): Boolean =
    !(token.text.length > 2 && token.text(2) == '"')

  def codeRefactorAction(
      title: String,
      uri: String,
      edits: List[l.TextEdit],
  ): l.CodeAction = {
    val codeAction = new l.CodeAction()
    codeAction.setTitle(title)
    codeAction.setKind(l.CodeActionKind.Refactor)
    codeAction.setEdit(
      new l.WorkspaceEdit(Map(uri -> edits.asJava).asJava)
    )

    codeAction
  }
}

object StringActions {
  def multilineTitle: String = "Convert to multiline string"
  def interpolationTitle: String = "Convert to interpolation string"
  def removeInterpolationTitle: String = "Remove interpolation"
}
