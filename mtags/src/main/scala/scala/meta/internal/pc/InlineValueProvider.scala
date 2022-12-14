package scala.meta.internal.pc

import scala.meta._

import org.eclipse.{lsp4j => l}

final class InlineValueProvider(
    val refProvider: ReferenceProvider
) {
  private def definitionNeedsBrackets(rhs: String): Boolean =
    rhs.parse[Term].toOption match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.ApplyUnary) => true
      case _ => false
    }

  private def referenceNeedsBrackets(parentPos: Option[(Int, Int)]): Boolean = {
    parentPos.flatMap(t =>
      refProvider.text.slice(t._1, t._2).parse[Term].toOption
    ) match {
      case Some(_: Term.ApplyInfix) => true
      case Some(_: Term.ApplyUnary) => true
      case Some(_: Term.Select) => true
      case _ => false
    }
  }
  // We return result or error
  def getInlineTextEdits(
      isInlineAll: Boolean
  ): Either[String, List[l.TextEdit]] = {
    val allReferences = refProvider.result()
    val references: List[Reference] =
      allReferences.collect { case Left(v) => v }
    allReferences.filter(_.isRight) match {
      case Right(definition) :: Nil => {
        if (isInlineAll) inlineAll(definition, references)
        else inlineOne(definition, references)
      }
      case _ => Left(InlineValueProvider.Errors.didNotFindDefinition)
    }
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
          .find(_.range.equals(refProvider.range))
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
        definition.rangeOffsets._1,
        definition.rangeOffsets._2,
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
    var startWhiteSpaces = 0
    if (startOffset != 0) {
      while (
        source(startOffset - startWhiteSpaces - 1) == ' ' || source(
          startOffset - startWhiteSpaces - 1
        ) == '\t'
      ) {
        startWhiteSpaces += 1
      }
    }
    var endWhiteSpaces = 0
    while (
      source(endOffset + endWhiteSpaces) == ' ' || source(
        endOffset + endWhiteSpaces
      ) == '\t'
    ) {
      endWhiteSpaces += 1
    }
    if (source(endOffset + endWhiteSpaces) == '\n')
      new l.Range(
        new l.Position(
          range.getStart.getLine,
          range.getStart.getCharacter - startWhiteSpaces
        ),
        new l.Position(range.getEnd.getLine + 1, 0)
      )
    else
      new l.Range(
        new l.Position(
          range.getStart.getLine,
          range.getStart.getCharacter - startWhiteSpaces
        ),
        new l.Position(
          range.getEnd.getLine,
          range.getEnd.getCharacter + endWhiteSpaces
        )
      )
  }
}

object InlineValueProvider {
  object Errors {
    val didNotFindDefinition = "The definition was not found in the scope."
    val notLocal =
      "Non local value cannot be inlined."
  }
}
