package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken

import org.eclipse.{lsp4j => l}

class RemoveRedundantCast(buffers: Buffers) extends CodeAction {
  import RemoveRedundantCast._

  override def kind: String = l.CodeActionKind.QuickFix
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    val range = params.getRange()

    for {
      text <- buffers.get(path).orElse(path.readTextOpt).toSeq
      diagnostic <- params.getContext().getDiagnostics().asScala.toSeq
      if isRedundantCast(diagnostic)
      if range.overlapsWith(diagnostic.getRange())
      edit <- removeCastEdit(text, diagnostic.getRange()).toSeq
    } yield CodeActionBuilder.build(
      title,
      kind,
      diagnostics = List(diagnostic),
      changes = Seq(path -> Seq(edit)),
    )
  }
}

object RemoveRedundantCast {
  val title = "Remove redundant cast"

  private val RedundantCastCode = "compiler.warn.redundant.cast"

  private def isRedundantCast(diagnostic: l.Diagnostic): Boolean =
    Option(diagnostic.getCode()).exists(code =>
      code.isLeft() && code.getLeft() == RedundantCastCode
    )

  private def removeCastEdit(
      text: String,
      range: l.Range,
  ): Option[l.TextEdit] = {
    val start = positionToOffset(text, range.getStart())
    for {
      open <- findOpenParen(text, start)
      close <- findCloseParen(text, open + 1)
      if close > open
    } yield {
      val editEnd = skipHorizontalWhitespace(text, close + 1)
      val editStart =
        if (
          editEnd >= text.length || text
            .charAt(editEnd) == '\n' || text.charAt(editEnd) == '\r'
        )
          skipHorizontalWhitespaceBackwards(text, open)
        else
          open
      new l.TextEdit(
        new l.Range(
          text.indexToLspPosition(editStart),
          text.indexToLspPosition(editEnd),
        ),
        "",
      )
    }
  }

  private def findOpenParen(text: String, from: Int): Option[Int] = {
    var index = from.min(text.length - 1)
    var result = -1
    var continue = true
    while (continue && index >= 0) {
      text.charAt(index) match {
        case '(' =>
          result = index
          continue = false
        case '\n' | '\r' | ';' | '=' | ',' =>
          continue = false
        case _ =>
          index -= 1
      }
    }
    if (result >= 0) Some(result) else None
  }

  private def findCloseParen(text: String, from: Int): Option[Int] = {
    var index = from.max(0)
    var result = -1
    var continue = true
    while (continue && index < text.length) {
      text.charAt(index) match {
        case ')' =>
          result = index
          continue = false
        case '\n' | '\r' | ';' =>
          continue = false
        case _ =>
          index += 1
      }
    }
    if (result >= 0) Some(result) else None
  }

  private def skipHorizontalWhitespace(text: String, from: Int): Int = {
    var index = from
    while (
      index < text.length && {
        val ch = text.charAt(index)
        ch == ' ' || ch == '\t'
      }
    ) index += 1
    index
  }

  private def skipHorizontalWhitespaceBackwards(
      text: String,
      from: Int,
  ): Int = {
    var index = from - 1
    while (
      index >= 0 && (text.charAt(index) == ' ' || text.charAt(index) == '\t')
    )
      index -= 1
    index + 1
  }

  private def positionToOffset(text: String, position: l.Position): Int = {
    var line = 0
    var character = 0
    var offset = 0
    while (
      offset < text.length &&
      (line < position.getLine() || character < position.getCharacter())
    ) {
      if (text.charAt(offset) == '\n') {
        line += 1
        character = 0
      } else {
        character += 1
      }
      offset += 1
    }
    offset
  }
}
