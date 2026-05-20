package scala.meta.internal.pc

import scala.meta.internal.mtags.CommonMtagsEnrichments.extendRangeToIncludeWhiteCharsAndTheFollowingNewLine

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
    else if (definition.requiresCurlyBraces && ref.requiresCurlyBraces)
      new l.TextEdit(
        ref.range,
        s"""{${definition.rhs}}"""
      )
    else new l.TextEdit(ref.range, definition.rhs)

  private def definitionTextEdit(definition: Definition): l.TextEdit =
    new l.TextEdit(
      extend(
        definition.rangeOffsets.start,
        definition.rangeOffsets.end,
        definition.range
      ),
      ""
    )

  private def extend(
      startOffset: Int,
      endOffset: Int,
      range: l.Range
  ): l.Range = {
    val (startWithSpace, endWithSpace): (Int, Int) =
      extendRangeToIncludeWhiteCharsAndTheFollowingNewLine(
        text
      )(startOffset, endOffset)
    val startPos = new l.Position(
      range.getStart.getLine,
      range.getStart.getCharacter - (startOffset - startWithSpace)
    )
    val endPos =
      if (endWithSpace - 1 >= 0 && text(endWithSpace - 1) == '\n')
        new l.Position(range.getEnd.getLine + 1, 0)
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
    requiresCurlyBraces: Boolean,
    shouldBeRemoved: Boolean
)

case class Reference(
    range: l.Range,
    parentOffsets: Option[RangeOffset],
    requiresBrackets: Boolean,
    requiresCurlyBraces: Boolean
)
