package scala.meta.internal.pc

import scala.meta.internal.tokenizers.Chars

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.StdNames.*
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j as l}

enum CompletionKind:
  case Empty, Scope, Members

case class CompletionPos(
    kind: CompletionKind,
    start: Int,
    end: Int,
    query: String,
    cursorPos: SourcePosition
):

  def sourcePos: SourcePosition = cursorPos.withSpan(Spans.Span(start, end))

  def toEditRange: l.Range =
    def toPos(offset: Int): l.Position =
      new l.Position(
        cursorPos.source.offsetToLine(offset),
        cursorPos.source.column(offset)
      )

    new l.Range(toPos(start), toPos(end))
end CompletionPos

object CompletionPos:

  def infer(
      cursorPos: SourcePosition,
      text: String,
      treePath: List[Tree]
  )(using Context): CompletionPos =
    val start = inferIdentStart(cursorPos, text, treePath)
    val end = inferIdentEnd(cursorPos, text)
    val query = text.substring(start, end)
    val prevIsDot =
      if start - 1 >= 0 then text.charAt(start - 1) == '.' else false
    val kind =
      if query.isEmpty && !prevIsDot then CompletionKind.Empty
      else if prevIsDot then CompletionKind.Members
      else CompletionKind.Scope

    CompletionPos(
      kind,
      start,
      end,
      query,
      cursorPos
    )
  end infer

  /**
   * Returns the start offset of the identifier starting as the given offset position.
   */
  private def inferIdentStart(
      pos: SourcePosition,
      text: String,
      path: List[Tree]
  )(using Context): Int =
    def fallback: Int =
      var i = pos.point - 1
      while i >= 0 && Chars.isIdentifierPart(text.charAt(i)) do i -= 1
      i + 1
    def loop(enclosing: List[Tree]): Int =
      enclosing match
        case Nil => fallback
        case head :: tl =>
          if !head.sourcePos.contains(pos) then loop(tl)
          else
            head match
              case i: Ident => i.sourcePos.point
              case s: Select =>
                if s.name.toTermName == nme.ERROR || pos.span.point < s.span.point then
                  fallback
                else s.span.point
              case _ => fallback
    loop(path)
  end inferIdentStart

  /**
   * Returns the end offset of the identifier starting as the given offset position.
   */
  private def inferIdentEnd(pos: SourcePosition, text: String): Int =
    var i = pos.point
    while i < text.length && Chars.isIdentifierPart(text.charAt(i)) do i += 1
    i

end CompletionPos
