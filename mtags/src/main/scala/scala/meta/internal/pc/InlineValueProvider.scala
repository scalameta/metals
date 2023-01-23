package scala.meta.internal.pc

import scala.annotation.tailrec

import scala.meta._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.Definition
import scala.meta.internal.pc.Reference

import org.eclipse.{lsp4j => l}
final class InlineValueProvider(
    val refProvider: PcValReferenceProvider
) {
  private def definitionNeedsBrackets(rhs: String): Boolean =
    rhs.parse[Term].toOption match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.Function) => true
      case _ => false
    }

  private def referenceNeedsBrackets(
      parentPos: Option[RangeOffset]
  ): Boolean = {
    parentPos.flatMap(t =>
      refProvider.text.slice(t.start, t.end).parse[Term].toOption
    ) match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.ApplyUnary) => true
      case Some(_: Term.Select) => true
      case Some(_: Term.Name) => true // apply
      case _ => false
    }
  }
  // We return result or error
  def getInlineTextEdits(): Either[String, List[l.TextEdit]] = {
    refProvider
      .defAndRefs()
      .map { case (d, refs) =>
        if (d.termNameRange.encloses(refProvider.position)) inlineAll(d, refs)
        else inlineOne(d, refs)
      }
      .getOrElse(Left(InlineValueProvider.Errors.didNotFindDefinition))
  }

  private def inlineAll(
      definition: Definition,
      references: List[Reference]
  ): Either[String, List[l.TextEdit]] =
    if (!definition.isLocal) Left(InlineValueProvider.Errors.notLocal)
    else
      Right(
        definitionTextEdit(definition) :: (references.map(
          referenceTextEdit(definition)
        ))
      )

  private def inlineOne(
      definition: Definition,
      references: List[Reference]
  ): Either[String, List[l.TextEdit]] =
    if (definition.isLocal && references.length == 1)
      Right(
        definitionTextEdit(definition) :: (references.map(
          referenceTextEdit(definition)
        ))
      )
    else {
      Right(
        references
          .find(_.range.encloses(refProvider.position))
          .map(referenceTextEdit(definition))
          .toList
      )
    }

  private def referenceTextEdit(
      definition: Definition
  )(ref: Reference): l.TextEdit =
    if (
      definitionNeedsBrackets(definition.rhs) && referenceNeedsBrackets(
        ref.parentOffsets
      )
    )
      new l.TextEdit(ref.range, s"""(${definition.rhs})""")
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
    val source = refProvider.text
    @tailrec
    def expand(step: Int, currentIndex: Int): Int = {
      def isWhiteSpace =
        source(currentIndex) == ' ' || source(currentIndex) == '\t'
      if (currentIndex >= 0 && currentIndex < source.size && isWhiteSpace)
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
      if (endWithSpace < source.size && source(endWithSpace) == '\n')
        new l.Position(range.getEnd.getLine + 1, 0)
      else if (endWithSpace < source.size && source(endWithSpace) == ';')
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
      "Non local value cannot be inlined."
  }
}
