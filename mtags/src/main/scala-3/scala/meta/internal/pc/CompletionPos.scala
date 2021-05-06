package scala.meta.internal.pc

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._

import scala.meta.internal.tokenizers.Chars

import org.eclipse.{lsp4j => l}
import dotty.tools.dotc.util.SourcePosition

enum CompletionKind {
  case Empty, Scope, Members
}

case class CompletionPos(
    kind: CompletionKind,
    start: Int,
    end: Int,
    query: String,
    cursorPos: SourcePosition
) {

  def toEditRange: l.Range = {
    def toPos(offset: Int): l.Position =
      new l.Position(
        cursorPos.source.offsetToLine(offset),
        cursorPos.source.column(offset)
      )

    new l.Range(toPos(start), toPos(end))
  }
}

object CompletionPos {

  def infer(
      cursorPos: SourcePosition,
      text: String,
      treePath: List[Tree]
  )(using Context): CompletionPos = {
    val start = inferIdentStart(cursorPos, text, treePath)
    val end = inferIdentEnd(cursorPos, text)
    val query = text.substring(start, end)
    val prevIsDot = if (start - 1 >= 0) text.charAt(start - 1) == '.' else false
    val kind =
      if (query.isEmpty && !prevIsDot) CompletionKind.Empty
      else if (prevIsDot) CompletionKind.Members
      else CompletionKind.Scope

    CompletionPos(
      kind,
      start,
      end,
      query,
      cursorPos
    )
  }

  /**
   * Returns the start offset of the identifier starting as the given offset position.
   */
  private def inferIdentStart(
      pos: SourcePosition,
      text: String,
      path: List[Tree]
  )(using Context): Int = {
    def fallback: Int = {
      var i = pos.point - 1
      while (i >= 0 && Chars.isIdentifierPart(text.charAt(i))) {
        i -= 1
      }
      i + 1
    }
    def loop(enclosing: List[Tree]): Int =
      enclosing match {
        case Nil => fallback
        case head :: tl =>
          if (!head.sourcePos.contains(pos)) loop(tl)
          else {
            head match {
              case i: Ident => i.sourcePos.point
              case Select(qual, _) => qual.endPos.point + 1
              case _ => fallback
            }
          }
      }
    loop(path)
  }

  /**
   * Returns the end offset of the identifier starting as the given offset position.
   */
  private def inferIdentEnd(pos: SourcePosition, text: String): Int = {
    var i = pos.point
    while (i < text.length && Chars.isIdentifierPart(text.charAt(i))) {
      i += 1
    }
    i
  }
}
