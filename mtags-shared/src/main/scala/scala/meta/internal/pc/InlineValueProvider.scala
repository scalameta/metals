package scala.meta.internal.pc

import scala.annotation.tailrec

import org.eclipse.{lsp4j => l}

trait InlineValueProvider {

  val text: Array[Char]
  val position: l.Position
  def defAndRefs(): Either[String, (Definition, List[Reference])]

  // We return a result or an error
  def getInlineTextEdits(): Either[String, List[l.TextEdit]] =
    defAndRefs() match {
      case Right((defn, refs)) =>
        val edits =
          if (defn.shouldBeRemoved) {
            val defEdit = definitionTextEdit(defn)
            val refsEdits = refs.map(referenceTextEdit(defn))
            defEdit :: refsEdits
          } else refs.map(referenceTextEdit(defn))
        Right(edits)
      case Left(error) => Left(error)
    }

  private def referenceTextEdit(
      definition: Definition
  )(ref: Reference): l.TextEdit =
    if (definition.requiresBrackets && ref.requiresBrackets)
      new l.TextEdit(
        ref.range,
        s"""(${definition.rhs})"""
      )
    else new l.TextEdit(ref.range, definition.rhs)

  private def definitionTextEdit(definition: Definition): l.TextEdit =
    new l.TextEdit(
      extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(
        definition.rangeOffsets.start,
        definition.rangeOffsets.end,
        definition.range
      ),
      ""
    )

  private def extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(
      startOffset: Int,
      endOffset: Int,
      range: l.Range
  ): l.Range = {
    @tailrec
    def expand(step: Int, currentIndex: Int): Int = {
      def isWhiteSpace =
        text(currentIndex) == ' ' || text(currentIndex) == '\t'
      if (currentIndex >= 0 && currentIndex < text.size && isWhiteSpace)
        expand(step, currentIndex + step)
      else currentIndex
    }
    val endWithSpace = expand(1, endOffset)
    val startWithSpace = expand(-1, startOffset - 1)
    val startPos = new l.Position(
      range.getStart.getLine,
      range.getStart.getCharacter - (startOffset - startWithSpace) + 1
    )
    val endPos =
      if (endWithSpace < text.size && text(endWithSpace) == '\n')
        new l.Position(range.getEnd.getLine + 1, 0)
      else if (endWithSpace < text.size && text(endWithSpace) == ';')
        new l.Position(
          range.getEnd.getLine,
          range.getEnd.getCharacter + endWithSpace - endOffset + 1
        )
      else
        new l.Position(
          range.getEnd.getLine,
          range.getEnd.getCharacter + endWithSpace - endOffset
        )

    new l.Range(startPos, endPos)
  }
}

object InlineValueProvider {
  object Errors {
    val didNotFindDefinition =
      "The definition was not found in the scope of the file."
    val notLocal =
      "Non-local value cannot be inlined."
    val didNotFindReference =
      "The chosen reference couldn't be identified."
    def variablesAreShadowed(fullName: String): String =
      s"Following variables are shadowed: $fullName."
  }
}

case class RangeOffset(start: Int, end: Int)

case class Definition(
    range: l.Range,
    rhs: String,
    rangeOffsets: RangeOffset,
    requiresBrackets: Boolean,
    shouldBeRemoved: Boolean
)

case class Reference(
    range: l.Range,
    parentOffsets: Option[RangeOffset],
    requiresBrackets: Boolean
)
